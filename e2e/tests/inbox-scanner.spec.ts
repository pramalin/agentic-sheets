import { test, expect, request, type APIRequestContext } from "@playwright/test";
import * as fs from "node:fs";
import * as path from "node:path";
import { fileURLToPath } from "node:url";

// __dirname doesn't exist in ESM (this project's e2e/package.json sets
// "type": "module") -- the standard equivalent, derived from this
// file's own import.meta.url.
const __dirname = path.dirname(fileURLToPath(import.meta.url));

const BACKEND = process.env.E2E_BACKEND_URL ?? "http://localhost:8081";
const LLMSIM = process.env.E2E_LLMSIM_URL ?? "http://localhost:8089";
const API_KEY = process.env.E2E_API_KEY ?? "e2e-test-key";
const INBOX_HOST_DIR = process.env.E2E_INBOX_HOST_DIR;

/**
 * The automated version of the exact by-hand sequence this project's
 * own first real run of Step 9 walked through: drop a file in the
 * inbox, wait for the real scheduled scan to discover and propose it,
 * approve through the API, confirm delivery, confirm the archiver's
 * real file move. Every piece of infrastructure this test drives
 * (InboxScanner, InboxFileRepository's atomic claim, WorksheetResolver,
 * MappingWorkflowService#proposeInitialFromInbox, InboxArchiver) was
 * built and unit-tested before ever running for real -- this is what
 * actually proves the pieces hold together as a pipeline, the same
 * role pipeline-api.spec.ts plays for the manual /propose path.
 *
 * Deliberately not a mocked or shortened scan -- compose.e2e-inbox.yaml
 * shortens the scanner's own intervals (stability/scan/archive), but
 * this test still waits through real scheduled cycles, not something
 * triggered directly. That's the point: it's exercising the actual
 * background machinery, not calling the same internal methods a unit
 * test already covers.
 *
 * Reuses the real JPMC Holdings fixture bytes (copied from
 * sample-input/, not synthesized) -- the scanner's route resolution
 * and worksheet resolution both depend on real client-configs/jpmc.yaml
 * routing and a real workbook structure, not something a fake file
 * could exercise meaningfully.
 */
test.describe("inbox: scanner discovers, proposes, and archiver moves on delivery", () => {
  let api: APIRequestContext;
  let llmsimApi: APIRequestContext;

  test.beforeAll(async () => {
    if (!INBOX_HOST_DIR) {
      throw new Error(
          "E2E_INBOX_HOST_DIR is not set -- this test only makes sense run via run-inbox-tests.sh, "
          + "which creates and passes through the isolated temp workspace it needs.");
    }
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

  test("real file in inbox -> scanned, proposed, approved, delivered, archived", async () => {
    await llmsimApi.post("/_llmsim/reset");

    // Today's date, not a fixed one -- each run gets a fresh, isolated
    // Postgres volume (see run-inbox-tests.sh's own project-name
    // isolation), so there's no cross-run collision risk either way;
    // "today" just reads more sensibly than an arbitrary hardcoded date
    // would over time.
    const today = new Date();
    const datePart = `${today.getFullYear()}${String(today.getMonth() + 1).padStart(2, "0")}${String(today.getDate()).padStart(2, "0")}`;
    const filename = `holdings_jpmc_${datePart}.xlsx`;

    const fixtureSource = path.resolve(__dirname, "../../sample-input/holdings_jpmc_20260115.xlsx");
    const inboxTarget = path.join(INBOX_HOST_DIR!, "inbox", filename);
    fs.copyFileSync(fixtureSource, inboxTarget);

    // The scanner's own stability window (2s) plus scan interval (3s)
    // both apply before it even attempts this file -- polling, not a
    // fixed sleep, since the exact timing depends on where in its own
    // cycle the scanner happened to be when the file landed.
    const proposal = await pollFor(async () => {
      const response = await api.get("/internal/mapping/proposals", { params: { status: "PENDING" } });
      const entries = await response.json();
      return entries.find((entry: { sourceFilename?: string }) =>
          entry.sourceFilename === `inbox/${filename}`);
    }, { timeoutMs: 30_000, intervalMs: 1_000, description: "scanner to discover and propose the file" });

    expect(proposal).toBeTruthy();
    expect(proposal.clientId).toBe("jpmc");
    expect(proposal.modelId).toBe("Holdings");

    // Approve exactly like a real reviewer would -- same endpoint
    // pipeline-api.spec.ts and the browser tests both already use.
    const approveResponse = await api.post(`/internal/mapping/proposals/${proposal.id}/approve`, {
      params: { reviewedBy: "e2e-inbox-test" },
    });
    expect(approveResponse.ok()).toBeTruthy();
    const approved = await approveResponse.json();
    expect(approved.validation.validRows.length).toBeGreaterThan(0);
    expect(approved.dispatch.outcome).toBe("SUCCESS");

    const detailResponse = await api.get(`/internal/mapping/proposals/${proposal.id}`);
    const detail = await detailResponse.json();
    expect(detail.proposal.status).toBe("APPROVED");
    expect(detail.batch.status).toBe("DELIVERED");

    // The archiver runs on its own separate schedule (3s interval) --
    // not triggered by approval itself, deliberately (see
    // InboxArchiver's own class javadoc). Polling the filesystem
    // directly, the same way this was confirmed by hand: the file
    // should be gone from inbox/ and present somewhere under
    // archive/delivered/jpmc/holdings/<date>/.
    const archiveDir = path.join(
        INBOX_HOST_DIR!, "archive", "delivered", "jpmc", "holdings",
        `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, "0")}-${String(today.getDate()).padStart(2, "0")}`);

    await pollFor(async () => {
      if (!fs.existsSync(archiveDir)) {
        return null;
      }
      const archived = fs.readdirSync(archiveDir).find((f) => f.endsWith(filename));
      return archived ?? null;
    }, { timeoutMs: 20_000, intervalMs: 1_000, description: "archiver to move the delivered file" });

    expect(fs.existsSync(inboxTarget)).toBe(false);
  });
});

async function pollFor<T>(
    check: () => Promise<T | null | undefined>,
    options: { timeoutMs: number; intervalMs: number; description: string }): Promise<T> {
  const deadline = Date.now() + options.timeoutMs;
  while (Date.now() < deadline) {
    const result = await check();
    if (result) {
      return result;
    }
    await new Promise((resolve) => setTimeout(resolve, options.intervalMs));
  }
  throw new Error(`Timed out after ${options.timeoutMs}ms waiting for: ${options.description}`);
}
