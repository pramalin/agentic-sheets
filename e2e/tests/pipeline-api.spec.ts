import { test, expect, request, type APIRequestContext } from "@playwright/test";

const BACKEND = process.env.E2E_BACKEND_URL ?? "http://localhost:8081";
const LLMSIM = process.env.E2E_LLMSIM_URL ?? "http://localhost:8089";
const API_KEY = process.env.E2E_API_KEY ?? "e2e-test-key";

/**
 * Black-box, end to end: real backend container, real Postgres, real
 * sheets-reader-mcp, llmsim standing in for the one genuinely
 * nondeterministic dependency, real validator and dispatcher, local
 * fake-target. This test runner knows nothing about Spring beans or
 * application internals -- it calls the same HTTP endpoints a real
 * client uses and checks the observable results, the same way the
 * curl-driven manual verification throughout this project's earlier
 * development already did, just automated and repeatable now.
 *
 * Deliberately asserts business results, not just terminal status --
 * DELIVERED alone doesn't prove the payload was actually correct, only
 * that dispatch didn't fail. A defect that delivers an empty or
 * mis-mapped payload while still returning 200 should still fail this
 * test.
 */
test.describe("golden path: propose -> approve -> validate -> dispatch", () => {
  let api: APIRequestContext;
  let llmsimApi: APIRequestContext;

  test.beforeAll(async () => {
    api = await request.newContext({
      baseURL: BACKEND,
      extraHTTPHeaders: { Authorization: `Bearer ${API_KEY}` },
    });
    llmsimApi = await request.newContext({ baseURL: LLMSIM });
  });

  test.afterAll(async () => {
    await api.dispose();
    await llmsimApi.dispose();
  });

  test("JPMC holdings: propose through delivery, exactly one model call", async () => {
    // Reset llmsim first -- makes this test independent of run order
    // (not relying on being the first proposal ever made against this
    // fixture) and rewinds the script back to its one scripted step.
    const resetResponse = await llmsimApi.post("/_llmsim/reset");
    expect(resetResponse.ok(), "llmsim reset should succeed").toBeTruthy();

    // 1. Propose
    const proposeResponse = await api.post("/internal/mapping/propose", {
      params: {
        modelId: "Holdings",
        clientId: "jpmc",
        path: "holdings_jpmc_20260115.xlsx",
        worksheet: "Holdings",
      },
    });
    expect(proposeResponse.ok(), await describeIfFailed(proposeResponse)).toBeTruthy();
    const proposed = await proposeResponse.json();
    const proposalId: number = proposed.mappingProposalId;
    expect(proposalId).toBeGreaterThan(0);

    // 2. Verify the proposal's actual content, not just that *something*
    // was created -- a defect could produce an empty or malformed
    // proposal while still returning 200.
    expect(proposed.proposal.fieldMappings.length).toBeGreaterThan(0);
    const accountField = proposed.proposal.fieldMappings.find(
      (f: { canonicalFieldPath: string }) => f.canonicalFieldPath === "account_id",
    );
    expect(accountField?.sourceColumn).toBe("Account");

    // 3. Confirm the proposal is genuinely PENDING and attached to the
    // right client/batch before doing anything else with it.
    const detailBeforeResponse = await api.get(`/internal/mapping/proposals/${proposalId}`);
    const detailBefore = await detailBeforeResponse.json();
    expect(detailBefore.proposal.status).toBe("PENDING");
    expect(detailBefore.batch.clientId).toBe("jpmc");

    // 4. Approve
    const approveResponse = await api.post(`/internal/mapping/proposals/${proposalId}/approve`, {
      params: { reviewedBy: "e2e-golden-path" },
    });
    expect(approveResponse.ok(), await describeIfFailed(approveResponse)).toBeTruthy();
    const approved = await approveResponse.json();

    // 5. Business results from the approve response itself
    expect(approved.validation.validRows.length).toBeGreaterThan(0);
    expect(approved.validation.rowErrors.length).toBe(0);
    expect(approved.dispatch?.outcome).toBe("SUCCESS");

    // 6. Re-fetch detail -- confirms the *durable* state agrees with
    // what the approve call's own response claimed, not just that the
    // response looked right in the moment.
    const detailAfterResponse = await api.get(`/internal/mapping/proposals/${proposalId}`);
    const detailAfter = await detailAfterResponse.json();
    expect(detailAfter.proposal.status).toBe("APPROVED");
    expect(detailAfter.batch.status).toBe("DELIVERED");
    expect(detailAfter.validationRuns.length).toBe(1);
    expect(detailAfter.validationRuns[0].invalidRowCount).toBe(0);
    expect(detailAfter.deliveryLog.length).toBe(1);
    expect(detailAfter.deliveryLog[0].outcome).toBe("SUCCESS");
    expect(detailAfter.deliveryLog[0].statusCode).toBe(200);

    // 7. Confirm the agent was called exactly once. This is the actual
    // point of resetting llmsim and using Script.exactly rather than
    // repeatingLast/cycling: a regression that makes a wasted second
    // model call (the exact class of bug Step 7.4/7.5's hardening
    // rounds found and fixed) fails this test instead of passing
    // silently. Not asserting the prompt text verbatim, or a specific
    // model string -- application.yml deliberately never pins one
    // either ("a hardcoded model string here would just go stale over
    // time"), relying on the Spring AI starter's own maintained
    // default instead. A real first run of this exact test proved that
    // reasoning right: it moved from gpt-4o-mini to gpt-5-mini between
    // when this assertion was first written and when it was actually
    // run. Asserting *some* non-empty model was requested still catches
    // a real class of bug (the field silently missing or empty) without
    // reintroducing the staleness the application code itself already
    // decided to avoid.
    const callsResponse = await llmsimApi.get("/_llmsim/calls");
    const calls = await callsResponse.json();
    expect(calls.length, "llmsim should have received exactly one call").toBe(1);
    expect(calls[0].provider).toBe("openai");
    expect(typeof calls[0].model).toBe("string");
    expect(calls[0].model.length).toBeGreaterThan(0);
    expect(calls[0].outcome.type).toBe("responded");
  });
});

/** A readable failure message on assertion failure, instead of just
  * "expected true, got false" -- shows the actual response body so a
  * failing run in CI doesn't need a second round-trip to find out why. */
async function describeIfFailed(response: { ok: () => boolean; status: () => number; text: () => Promise<string> }) {
  if (response.ok()) return "ok";
  return `expected 2xx, got ${response.status()}: ${await response.text()}`;
}
