import { test, expect, request, type APIRequestContext } from "@playwright/test";

const BACKEND = process.env.E2E_BACKEND_URL ?? "http://localhost:8081";
const LLMSIM = process.env.E2E_LLMSIM_URL ?? "http://localhost:8089";
const API_KEY = process.env.E2E_API_KEY ?? "e2e-test-key";

/**
 * The one browser journey this project's testing strategy has been
 * building toward since Checkpoint A: does the actual review screen a
 * human uses correctly reflect a real approve, not just the API
 * underneath it. pipeline-api.spec.ts proves the pipeline works;
 * this proves the UI a reviewer actually looks at tells the truth
 * about it.
 *
 * Seeded via the API (the same `api` context pattern as the golden-path
 * test), then driven entirely through the real browser from there --
 * this is deliberately not "click everything," just the one path that
 * matters: open the queue, find the proposal, review it, approve it,
 * see the result. Independent of pipeline-api.spec.ts -- resets llmsim
 * and the fake-target journal itself rather than assuming either ran
 * first, so test order never matters.
 */
test.describe("browser: review and approve a real proposal", () => {
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

  test("propose via API, approve via the real review screen", async ({ page }) => {
    await llmsimApi.post("/_llmsim/reset");
    await api.post("/internal/fake-target/_journal/reset");

    const proposeResponse = await api.post("/internal/mapping/propose", {
      params: {
        modelId: "Holdings",
        clientId: "jpmc",
        path: "holdings_jpmc_20260115.xlsx",
        worksheet: "Holdings",
      },
    });
    expect(proposeResponse.ok()).toBeTruthy();
    const proposed = await proposeResponse.json();
    const proposalId: number = proposed.mappingProposalId;

    // Enter the API key -- ApiKeyGate is a real gate, not bypassed.
    await page.goto("/");
    await page.getByPlaceholder("API key").fill(API_KEY);
    await page.getByRole("button", { name: "Continue" }).click();

    // The queue: confirm the seeded proposal actually shows up with
    // real context, not just that *a* row exists.
    await expect(page.getByRole("heading", { name: "Review queue" })).toBeVisible();
    const queueRow = page.getByRole("row").filter({ hasText: "jpmc" });
    await expect(queueRow).toBeVisible();
    await expect(queueRow).toContainText("holdings_jpmc_20260115.xlsx");
    await queueRow.click();

    // The review screen: confirm it's showing the right proposal with
    // real field-mapping content, not a blank or loading state.
    await expect(page.getByRole("heading", { name: `Proposal ${proposalId}` })).toBeVisible();
    await expect(page.getByText("jpmc").first()).toBeVisible();
    await expect(page.getByRole("cell", { name: "account_id", exact: true })).toBeVisible();
    await expect(page.getByText("Account", { exact: true }).first()).toBeVisible();

    // Approve, as a real reviewer would.
    await page.locator("#reviewedBy").fill("e2e-browser-test");
    await page.getByRole("button", { name: "Approve", exact: true }).click();

    // The UI's own success message. Reusing this same element handle
    // for the SUCCESS check below, rather than a second independent
    // getByText search -- "Dispatch: " and "SUCCESS" render as
    // separate text/element nodes (a plain text node, then a nested
    // <strong>), and a regex spanning both in one fresh locator search
    // isn't something to depend on the exact cross-element matching
    // behavior of.
    const resultBox = page.getByText(/Approved\./);
    await expect(resultBox).toBeVisible({ timeout: 20_000 });
    await expect(resultBox).toContainText("SUCCESS");

    // The header pills updating is the actual point of this test --
    // not just that an API call succeeded somewhere, but that the
    // screen a reviewer is looking at reflects it.
    await expect(page.getByText("Decision:")).toBeVisible();
    await expect(page.getByText("Approved", { exact: true })).toBeVisible();
    await expect(page.getByText("Delivered", { exact: true })).toBeVisible();

    // Cross-check against the backend directly -- the UI showing the
    // right thing and the durable state actually being right are two
    // different claims; both get checked.
    const detailResponse = await api.get(`/internal/mapping/proposals/${proposalId}`);
    const detail = await detailResponse.json();
    expect(detail.proposal.status).toBe("APPROVED");
    expect(detail.batch.status).toBe("DELIVERED");
  });
});
