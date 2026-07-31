import { useState } from "react";
import { getStoredApiKey, getStoredReviewedBy, setStoredApiKey, setStoredReviewedBy } from "../api/client";
import styles from "./SettingsMenu.module.css";

/**
 * Closes a real gap an external review caught: QueuePage's error state
 * tells a reviewer with a wrong or expired key to "check the API key in
 * Settings," but no Settings control existed anywhere -- ApiKeyGate
 * only ever showed the entry form once, before any key was stored, with
 * no way back to it short of manually clearing browser storage. This is
 * that reachable control.
 *
 * "Change API key" and "Clear credentials" both reload the page after
 * writing to localStorage rather than trying to reset in-memory app
 * state piecemeal -- simpler, and correctly re-initializes everything
 * (including re-showing ApiKeyGate) the same way a fresh page load
 * would, with no risk of some component holding onto stale state after
 * the credential underneath it changed.
 */
export function SettingsMenu() {
  const [open, setOpen] = useState(false);
  const [reviewerName, setReviewerName] = useState(getStoredReviewedBy());
  const [newKey, setNewKey] = useState("");

  function saveReviewerName(name: string) {
    setReviewerName(name);
    setStoredReviewedBy(name);
  }

  function applyNewKey() {
    if (!newKey.trim()) return;
    setStoredApiKey(newKey.trim());
    window.location.reload();
  }

  function clearCredentials() {
    if (!window.confirm("Clear the stored API key and reviewer name? You'll need to re-enter the key to continue.")) {
      return;
    }
    setStoredApiKey("");
    setStoredReviewedBy("");
    window.location.reload();
  }

  return (
    <div className={styles.wrap}>
      <button className={styles.trigger} onClick={() => setOpen((o) => !o)}>
        ⚙ Settings
      </button>
      {open && (
        <div className={styles.panel}>
          <label className={styles.label} htmlFor="settings-reviewer-name">
            Reviewer name
          </label>
          <input
            id="settings-reviewer-name"
            className={styles.input}
            value={reviewerName}
            onChange={(e) => saveReviewerName(e.target.value)}
          />

          <label className={styles.label} htmlFor="settings-new-key">
            Change API key
          </label>
          <div className={styles.row}>
            <input
              id="settings-new-key"
              className={styles.input}
              type="password"
              value={newKey}
              onChange={(e) => setNewKey(e.target.value)}
              placeholder={getStoredApiKey() ? "•••• (currently set)" : "not set"}
            />
            <button className={styles.applyButton} onClick={applyNewKey}>
              Apply
            </button>
          </div>

          <button className={styles.dangerButton} onClick={clearCredentials}>
            Clear credentials
          </button>
        </div>
      )}
    </div>
  );
}
