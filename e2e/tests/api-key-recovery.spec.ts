import { test, expect } from "@playwright/test";

const API_KEY = process.env.E2E_API_KEY ?? "e2e-test-key";

/**
 * Regression coverage for a real, confirmed UX bug this project fixed
 * in Step 8c: QueuePage's error state told a reviewer with a wrong or
 * expired key to "check the API key in Settings," but no Settings
 * control existed anywhere -- ApiKeyGate only ever showed the entry
 * form once, before any key was stored, with no way back to it short
 * of manually clearing browser storage. `SettingsMenu` fixed that;
 * this locks it in so a future change can't quietly remove the way
 * back.
 *
 * No API/llmsim setup needed -- this test never creates a proposal,
 * it's purely about the auth-recovery path itself.
 */
test.describe("browser: recovering from a wrong API key", () => {
  test("wrong key shows a clear error with a real way back, not a dead end", async ({ page }) => {
    await page.goto("/");
    await page.getByPlaceholder("API key").fill("definitely-the-wrong-key");
    await page.getByRole("button", { name: "Continue" }).click();

    // A wrong key still satisfies ApiKeyGate's own check (something
    // was entered) -- the app renders, then the queue's own fetch
    // fails with a real, visible 401, not a silent blank screen.
    await expect(page.getByText(/Couldn't load the queue/)).toBeVisible();
    await expect(page.getByText(/Check the API key in Settings/)).toBeVisible();

    // The actual fix: Settings is reachable from here.
    await page.getByRole("button", { name: "⚙ Settings" }).click();
    await page.locator("#settings-new-key").fill(API_KEY);
    await page.getByRole("button", { name: "Apply" }).click();

    // Applying reloads the page -- confirm the queue now loads
    // cleanly with the corrected key, not still showing the old error.
    await expect(page.getByRole("heading", { name: "Review queue" })).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText(/Couldn't load the queue/)).not.toBeVisible();
  });
});
