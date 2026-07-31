import { defineConfig } from "@playwright/test";

/**
 * Checkpoint A: API-only tests (pipeline-api.spec.ts), using
 * Playwright's `request` fixture / APIRequestContext -- no browser
 * involved, despite this being Playwright Test. One TypeScript test
 * framework for both this and the browser tests Checkpoint B will add
 * (review-approval.spec.ts, api-key-recovery.spec.ts), rather than a
 * separate tool for black-box API testing.
 *
 * `workers: 1` in CI, not locally -- Playwright's own recommendation
 * for reproducibility, particularly relevant here since these tests
 * share one running llmsim instance (reset between tests, not
 * restarted), not isolated per-worker infrastructure.
 */
export default defineConfig({
  testDir: "./tests",
  timeout: 60_000,
  fullyParallel: false,
  workers: process.env.CI ? 1 : undefined,
  reporter: process.env.CI ? [["html", { open: "never" }]] : "list",
  use: {
    baseURL: process.env.E2E_BACKEND_URL ?? "http://localhost:8081",
  },
});
