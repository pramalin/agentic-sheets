import { test, expect, request, type APIRequestContext } from "@playwright/test";
import * as fs from "node:fs";
import * as path from "node:path";
import { fileURLToPath } from "node:url";

// __dirname doesn't exist in ESM (this project's e2e/package.json sets
// "type": "module") -- same fix as inbox-scanner.spec.ts needed first.
const __dirname = path.dirname(fileURLToPath(import.meta.url));

const BACKEND = process.env.E2E_BACKEND_URL ?? "http://localhost:8081";
const LLMSIM = process.env.E2E_LLMSIM_URL ?? "http://localhost:8089";
const API_KEY = process.env.E2E_API_KEY ?? "e2e-test-key";

/**
 * Live proof of Step 10's central claim: a second file with the exact
 * same column structure as a previously, cleanly-approved one reuses
 * the remembered mapping instead of calling the model again. Everything
 * behind this (ColumnFingerprint, MappingMemoryEligibility,
 * MappingResolutionService, MappingMemoryService) was unit- and
 * integration-tested before this ever ran for real -- this is what
 * actually proves the pieces hold together against a real running
 * stack, the same role pipeline-api.spec.ts plays for the manual
 * propose path it's built on top of.
 *
 * Uses a distinct client id ("jpmc-memtest", see client-configs/jpmc-memtest.yaml)
 * rather than "jpmc" itself, even though the underlying file bytes are
 * copies of jpmc's own real fixture -- a real, live-run-only bug this
 * test's first version had: Step 10's memory scope is keyed on client/
 * worksheet/model/fingerprints, not filename, so once this test
 * promoted an eligible mapping for (jpmc, Holdings, Holdings, ...),
 * pipeline-api.spec.ts's own propose call against the same
 * byte-identical fixture also hit that memory entry and skipped the
 * model call *it* expected -- correct Step 10 behavior, but a genuine
 * cross-test dependency that violated pipeline-api.spec.ts's own
 * "independent of run order" guarantee. A distinct client keeps this
 * test's own memory scope from ever overlapping with any other test's.
 *
 * Deliberately reuses the real JPMC Holdings fixture's own bytes for
 * both files (copied under two new names, not a synthesized workbook)
 * -- same underlying column structure, genuinely different file
 * identity for each (neither name is pipeline-api.spec.ts's own
 * holdings_jpmc_20260115.xlsx, so this test's own batches never
 * collide with that test's), so this is testing real memory reuse
 * across two distinct batches, not coincidentally hitting
 * import_batch's own dedup instead.
 *
 * llmsim is scripted with exactly one reply (see
 * e2e/llmsim/HoldingsHappyPath.scala) and reset only once, at the very
 * start -- deliberately not reset between the two propose calls. If
 * Step 10's wiring had a bug and the second propose call accidentally
 * still invoked the model, llmsim's own Script.exactly enforcement
 * would fail loudly on the unscripted second call, on top of this
 * test's own explicit assertion that exactly one call happened.
 */
test.describe("mapping memory: a second structurally-identical file reuses it", () => {
  let api: APIRequestContext;
  let llmsimApi: APIRequestContext;

  // JPMC's real agent-generated mapping (confirmed via this test's own
  // self-diagnosing check, on a real run) uses selectedVariant("USD")
  // on currency, since every row in this fixture happens to be the
  // same currency -- genuinely ineligible for Step 10A memory as-is.
  // Rather than switch fixtures (PIMCO's MarketRateBookValue would risk
  // a worse problem: llmsim's script -- HoldingsHappyPath.scala -- is
  // specifically scripted for Holdings-shaped prompts, so a
  // MarketRateBookValue request would likely get back Holdings-shaped
  // content that fails structural validation against a completely
  // different model), this test amends the proposal before approving
  // it -- a genuinely realistic reviewer action, and exactly the case
  // this design was built to handle: a human amendment should be
  // remembered the same as an agent proposal, provided its own content
  // is eligible.
  const firstFilename = "holdings_jpmc_20260115_memtest_a.xlsx";
  const secondFilename = "holdings_jpmc_20260115_memtest_b.xlsx";
  const firstFilePath = path.resolve(__dirname, "../../sample-input", firstFilename);
  const secondFilePath = path.resolve(__dirname, "../../sample-input", secondFilename);

  test.beforeAll(async () => {
    api = await request.newContext({
      baseURL: BACKEND,
      extraHTTPHeaders: { Authorization: `Bearer ${API_KEY}` },
    });
    llmsimApi = await request.newContext({ baseURL: LLMSIM });

    const originalFixture = path.resolve(__dirname, "../../sample-input/holdings_jpmc_20260115.xlsx");
    fs.copyFileSync(originalFixture, firstFilePath);
    fs.copyFileSync(originalFixture, secondFilePath);
  });

  test.afterAll(async () => {
    await api.dispose();
    await llmsimApi.dispose();
    // Leave sample-input/ exactly as this test found it.
    for (const p of [firstFilePath, secondFilePath]) {
      if (fs.existsSync(p)) {
        fs.unlinkSync(p);
      }
    }
  });

  test("second file with identical structure skips the model, first stays untouched", async () => {
    const resetResponse = await llmsimApi.post("/_llmsim/reset");
    expect(resetResponse.ok(), "llmsim reset should succeed").toBeTruthy();
    const fakeTargetResetResponse = await api.post("/internal/fake-target/_journal/reset");
    expect(fakeTargetResetResponse.ok(), "fake-target reset should succeed").toBeTruthy();

    // 1. First file -- a real agent call, same as the golden path.
    const firstPropose = await api.post("/internal/mapping/propose", {
      params: { modelId: "Holdings", clientId: "jpmc-memtest", path: firstFilename, worksheet: "Holdings" },
    });
    expect(firstPropose.ok(), await describeIfFailed(firstPropose)).toBeTruthy();
    const first = await firstPropose.json();
    const firstProposalId: number = first.mappingProposalId;

    const firstDetailBefore = await (await api.get(`/internal/mapping/proposals/${firstProposalId}`)).json();
    expect(firstDetailBefore.proposal.origin, "the first proposal must come from a real agent call").toBe("AGENT");

    // 2. Amend it: JPMC's real agent-generated mapping uses
    // selectedVariant("USD") on currency (confirmed via a real run of
    // this test before this step existed) -- genuinely ineligible for
    // Step 10A memory as agent-generated. Transform any
    // selectedVariant-based field into an equivalent
    // variantValueMap-based one (the observed source value mapping to
    // itself, matching how asset_class's own variantValueMap already
    // works) -- a realistic reviewer action ("I'd rather this be an
    // explicit per-row lookup"), and exactly the case an earlier design
    // review specifically called for: a human amendment should be
    // remembered the same as an agent proposal, provided its own
    // content is eligible. This test doesn't attempt to handle
    // sourceConstant fields the same way -- none appear in JPMC's real
    // mapping, so there was nothing to generalize that transform
    // against without guessing.
    type FieldMapping = {
      canonicalFieldPath: string; sourceColumn?: string; sourceConstant?: string;
      selectedVariant?: string; variantValueMap?: Record<string, string>;
      transformations: unknown[]; confidence: number; conversionNotes?: string;
    };
    const originalFieldMappings = first.proposal.fieldMappings as FieldMapping[];
    const stillDisqualifiedBySourceConstant = originalFieldMappings.filter((f) => f.sourceConstant);
    expect(stillDisqualifiedBySourceConstant, "this test's amend-based transform only handles selectedVariant, "
        + "not sourceConstant -- if this fails, JPMC's real mapping has grown a sourceConstant field and this "
        + "test needs a real transform for that case too, not just a wider net").toEqual([]);

    const amendedFieldMappings = originalFieldMappings.map((f) => {
      if (!f.selectedVariant) {
        return f;
      }
      return {
        ...f,
        selectedVariant: undefined,
        variantValueMap: { [f.selectedVariant]: f.selectedVariant },
      };
    });
    const amendedProposal = { ...first.proposal, fieldMappings: amendedFieldMappings };

    const amendResponse = await api.post(`/internal/mapping/proposals/${firstProposalId}/amend`, {
      data: amendedProposal,
    });
    expect(amendResponse.ok(), await describeIfFailed(amendResponse)).toBeTruthy();
    const amendedResult = await amendResponse.json();
    const amendedProposalId: number = amendedResult.mappingProposalId;
    expect(amendedProposalId, "amending should supersede the original and create a new proposal id")
        .not.toBe(firstProposalId);

    const amendedDetail = await (await api.get(`/internal/mapping/proposals/${amendedProposalId}`)).json();
    expect(amendedDetail.proposal.origin, "an amended proposal's origin must reflect the human edit, not AGENT")
        .toBe("HUMAN_AMENDMENT");
    const amendedDisqualifying = (amendedDetail.proposal.proposal.fieldMappings as FieldMapping[])
        .filter((f) => f.sourceConstant || f.selectedVariant);
    expect(amendedDisqualifying, "the amendment itself should have removed every disqualifying field").toEqual([]);

    // 3. Approve the amended version -- zero row errors is what makes
    // this eligible for promotion into memory at all (see
    // MappingMemoryService's own gating, confirmed by
    // MappingMemoryServiceTest -- status alone is not sufficient).
    const approveResponse = await api.post(`/internal/mapping/proposals/${amendedProposalId}/approve`, {
      params: { reviewedBy: "e2e-memory-test" },
    });
    expect(approveResponse.ok(), await describeIfFailed(approveResponse)).toBeTruthy();
    const approved = await approveResponse.json();
    expect(approved.validation.rowErrors.length, "the amended approval must validate cleanly to be promotable")
        .toBe(0);

    // 4. Second file -- same structure, genuinely different batch. This
    // is the actual claim under test.
    const secondPropose = await api.post("/internal/mapping/propose", {
      params: { modelId: "Holdings", clientId: "jpmc-memtest", path: secondFilename, worksheet: "Holdings" },
    });
    expect(secondPropose.ok(), await describeIfFailed(secondPropose)).toBeTruthy();
    const second = await secondPropose.json();
    const secondProposalId: number = second.mappingProposalId;
    expect(secondProposalId, "the second propose must create a genuinely new proposal, not return the first's")
        .not.toBe(firstProposalId);

    const secondDetail = await (await api.get(`/internal/mapping/proposals/${secondProposalId}`)).json();
    expect(secondDetail.proposal.origin, "the second proposal should be a memory reuse, not another agent call")
        .toBe("MEMORY");
    expect(secondDetail.proposal.mappingMemoryId).toBeTruthy();
    expect(secondDetail.batch.id, "the second proposal must attach to its own batch, not the first file's")
        .not.toBe(approved.importBatchId);

    // 5. The reused mapping should match what was actually approved --
    // the amended content, not the original pre-amendment agent output
    // (which still had selectedVariant on currency, and was never the
    // thing that got promoted).
    expect(secondDetail.proposal.proposal.fieldMappings.length)
        .toBe(amendedFieldMappings.length);
    const secondAccountField = secondDetail.proposal.proposal.fieldMappings.find(
      (f: { canonicalFieldPath: string }) => f.canonicalFieldPath === "account_id",
    );
    expect(secondAccountField?.sourceColumn).toBe("Account");
    const secondCurrencyField = secondDetail.proposal.proposal.fieldMappings.find(
      (f: { canonicalFieldPath: string }) => f.canonicalFieldPath === "currency",
    );
    expect(secondCurrencyField?.selectedVariant, "the remembered mapping should reflect the amendment, "
        + "not the original agent output").toBeFalsy();
    expect(secondCurrencyField?.variantValueMap).toEqual({ USD: "USD" });

    // 6. The explicit point of this whole test: exactly one model call
    // total, across both propose calls, not two.
    const callsResponse = await llmsimApi.get("/_llmsim/calls");
    const calls = await callsResponse.json();
    expect(calls.length, "llmsim should have received exactly one call across both propose calls -- "
        + "the second should have been a pure memory reuse").toBe(1);

    // 7. The amended, approved proposal's own record is untouched by
    // the second propose call -- still APPROVED, not somehow affected
    // by a later, unrelated batch reusing its remembered mapping. The
    // original pre-amendment proposal is SUPERSEDED, not APPROVED --
    // amending replaces it, it doesn't coexist alongside it.
    const amendedDetailAfter = await (await api.get(`/internal/mapping/proposals/${amendedProposalId}`)).json();
    expect(amendedDetailAfter.proposal.status).toBe("APPROVED");
    const firstDetailAfter = await (await api.get(`/internal/mapping/proposals/${firstProposalId}`)).json();
    expect(firstDetailAfter.proposal.status).toBe("SUPERSEDED");
  });
});

async function describeIfFailed(response: { ok: () => boolean; status: () => number; text: () => Promise<string> }) {
  if (response.ok()) return "ok";
  return `expected 2xx, got ${response.status()}: ${await response.text()}`;
}
