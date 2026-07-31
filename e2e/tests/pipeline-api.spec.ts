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
    // Reset llmsim and the fake-target journal first -- makes this test
    // independent of run order (not relying on being the first
    // proposal ever made against this fixture, or the first delivery
    // ever sent) and rewinds llmsim's script back to its one scripted
    // step.
    const resetResponse = await llmsimApi.post("/_llmsim/reset");
    expect(resetResponse.ok(), "llmsim reset should succeed").toBeTruthy();
    const fakeTargetResetResponse = await api.post("/internal/fake-target/reset");
    expect(fakeTargetResetResponse.ok(), "fake-target reset should succeed").toBeTruthy();

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

    // 7. What actually crossed the delivery boundary -- not just that
    // dispatch reported SUCCESS. FakeTargetController accepts any body
    // and always returns success; without reading its own journal back,
    // a regression sending an empty array, wrong field values, or
    // missing headers would report success identically. This is the
    // actual fix for the gap an external review correctly caught: the
    // test previously verified everything *up to* the delivery
    // boundary, but nothing that actually crossed it.
    const fakeTargetRequestsResponse = await api.get("/internal/fake-target/holdings/requests");
    const fakeTargetRequests = await fakeTargetRequestsResponse.json();
    expect(fakeTargetRequests.length, "fake-target should have received exactly one delivery").toBe(1);

    const delivery = fakeTargetRequests[0];
    expect(delivery.headers["x-import-batch-id"]).toBe(String(approved.importBatchId));
    expect(delivery.headers["x-mapping-proposal-id"]).toBe(String(proposalId));
    expect(delivery.headers["idempotency-key"]?.length).toBeGreaterThan(0);

    const deliveredRows = JSON.parse(delivery.body);
    expect(deliveredRows.length).toBe(approved.validation.validRows.length);
    // Representative exact values from the real JPMC fixture, not just
    // "some rows arrived" -- the first row is deterministic (llmsim's
    // scripted reply is exactly one fixed response, not a live model
    // call that could reorder or vary anything).
    expect(deliveredRows[0].account_id).toBe("ACC-1001");
    expect(deliveredRows[0].security_id).toBe("037833100");
    expect(deliveredRows[0].asset_class).toEqual({ type: "Equity" });
    expect(deliveredRows[0].quantity).toBe(5000);
    expect(deliveredRows[0].currency).toEqual({ type: "USD" });

    // 8. Confirm the agent was called exactly once. This is the actual
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
