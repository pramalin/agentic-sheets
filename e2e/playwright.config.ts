import { defineConfig, devices } from "@playwright/test";

/**
 * Two projects, deliberately separate, sharing one config: `api`
 * (Checkpoint A's pipeline-api.spec.ts -- Playwright's `request`
 * fixture / APIRequestContext, no browser at all despite this being
 * Playwright Test) and `browser` (Checkpoint B's review-approval.spec.ts
 * and api-key-recovery.spec.ts -- real Chromium, the actual frontend).
 * One TypeScript test framework for both, rather than a separate tool
 * for black-box API testing.
 *
 * Each runner script (run-golden-path.sh, run-browser-tests.sh) passes
 * `--project=<name>` explicitly -- not relying on `testMatch` filtering
 * alone to keep the two apart, since an omitted `--project` would
 * otherwise run every project against whatever spec files happen to be
 * present.
 *
 * The Vite dev server (`webServer` below) only starts when
 * `E2E_START_FRONTEND` is set -- run-golden-path.sh never sets it, so
 * a pure API-only run never needs frontend/node_modules installed or
 * a browser downloaded, matching how it worked before this file grew a
 * second project.
 *
 * `workers: 1` always, not just in CI -- Playwright's own
 * recommendation for CI reproducibility, but the reasoning applies
 * locally too: both browser tests share one backend/Postgres/llmsim
 * environment (not isolated per-worker infrastructure), so running
 * them concurrently creates real resource contention a single reviewer
 * session never would. A real run caught this directly: the exact same
 * assertion that passed comfortably within 10s total on one run timed
 * out on a later run that took 20s total, with the log confirming two
 * workers had picked up both spec files at once.
 */
export default defineConfig({
  testDir: "./tests",
  timeout: 60_000,
  fullyParallel: false,
  workers: 1,
  reporter: process.env.CI ? [["html", { open: "never" }]] : "list",
  projects: [
    {
      name: "api",
      testMatch: /pipeline-api\.spec\.ts/,
      use: {
        baseURL: process.env.E2E_BACKEND_URL ?? "http://localhost:8081",
      },
    },
    {
      name: "browser",
      testMatch: /(review-approval|api-key-recovery)\.spec\.ts/,
      use: {
        ...devices["Desktop Chrome"],
        baseURL: process.env.E2E_FRONTEND_URL ?? "http://localhost:38173",
        trace: "retain-on-failure",
        screenshot: "only-on-failure",
      },
    },
  ],
  webServer: process.env.E2E_START_FRONTEND
    ? {
        // "npm run dev" alone is just `vite` (frontend/package.json),
        // no port flag baked in -- has to be passed through explicitly
        // via npm's own `--` argument-forwarding, or this would always
        // bind Vite's actual default (5173) regardless of
        // E2E_FRONTEND_URL below, silently mismatching it.
        command: `npm run dev -- --port ${process.env.E2E_FRONTEND_PORT ?? "38173"}`,
        cwd: "../frontend",
        url: process.env.E2E_FRONTEND_URL ?? "http://localhost:38173",
        // Never reuse, even locally -- a stray dev server already
        // running on this port (e.g. a developer's own `npm run dev`)
        // would otherwise get silently reused here, pointed at
        // whatever backend *it* was configured for, not this E2E run's
        // actual backend port.
        reuseExistingServer: false,
        env: {
          // vite.config.ts's proxy already reads this exact variable --
          // points the frontend's /internal/** proxy at the E2E
          // backend's actual port, not the plain-dev-stack default.
          AGENTIC_SHEETS_BACKEND_URL: process.env.E2E_BACKEND_URL ?? "http://localhost:8081",
        },
      }
    : undefined,
});
