import { useState, type ReactNode } from "react";
import { getStoredApiKey, setStoredApiKey } from "../api/client";

/**
 * Gates the app behind having an API key stored. Deliberately simple --
 * this is a single shared secret (see ApiKeyAuthFilter on the backend),
 * not a per-user login, so there's no username, no session, nothing to
 * validate here beyond "is something entered." An actually-wrong key
 * still surfaces clearly: every API call will come back 401, and
 * QueuePage's error state specifically calls out checking Settings when
 * that happens.
 */
export function ApiKeyGate({ children }: { children: ReactNode }) {
  const [hasKey, setHasKey] = useState(() => getStoredApiKey().length > 0);
  const [draft, setDraft] = useState("");

  if (hasKey) return <>{children}</>;

  return (
    <div
      style={{
        minHeight: "100vh",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        padding: 24,
      }}
    >
      <form
        onSubmit={(e) => {
          e.preventDefault();
          if (!draft.trim()) return;
          setStoredApiKey(draft.trim());
          setHasKey(true);
        }}
        style={{
          width: "100%",
          maxWidth: 380,
          background: "var(--surface)",
          border: "1px solid var(--border)",
          borderRadius: 14,
          padding: 28,
        }}
      >
        <h1 style={{ fontSize: 20, marginBottom: 8 }}>agentic-sheets</h1>
        <p style={{ color: "var(--text-secondary)", fontSize: 14, marginBottom: 20 }}>
          Enter the shared API key to access the review queue. This matches
          AGENTIC_SHEETS_API_KEY on the backend.
        </p>
        <input
          type="password"
          autoFocus
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          placeholder="API key"
          style={{
            width: "100%",
            padding: "10px 12px",
            borderRadius: 8,
            border: "1px solid var(--border)",
            background: "var(--bg)",
            color: "var(--text-primary)",
            fontSize: 14,
            marginBottom: 16,
            boxSizing: "border-box",
          }}
        />
        <button
          type="submit"
          style={{
            width: "100%",
            padding: "10px 12px",
            borderRadius: 8,
            border: "none",
            background: "var(--accent-info)",
            color: "#0b1324",
            fontWeight: 700,
            fontSize: 14,
            cursor: "pointer",
          }}
        >
          Continue
        </button>
      </form>
    </div>
  );
}
