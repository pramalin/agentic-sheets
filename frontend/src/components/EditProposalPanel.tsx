import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { ApiError, amendProposal } from "../api/client";
import type { MappingProposal } from "../api/types";
import styles from "./EditProposalPanel.module.css";

/**
 * The "edit" verb in "approve/edit/reject" -- a direct JSON editor
 * rather than individual per-field inputs for every property
 * (sourceColumn, variantValueMap, transformations, ...). A fully
 * per-field editing UI is real, substantial work in its own right
 * (nested variant maps and transformation arrays each need their own
 * add/remove interaction), and this is honestly scoped as a first,
 * fully-functional version rather than a half-built form -- a reviewer
 * can correct anything here, just as text rather than through
 * dedicated controls for each field type. Submitting goes through the
 * exact same structural validation as agent output (see
 * MappingProposalService.validateEdited on the backend), so a typo'd
 * field path or invalid variant name is caught before persisting, not
 * silently accepted because it came from a human instead of a model.
 *
 * Validates JSON continuously (every keystroke), not only on Save --
 * an external review correctly flagged that only catching malformed
 * JSON after clicking Save is a worse editing experience than it needs
 * to be, since the person has to notice the error, scroll back up, and
 * find whatever's wrong with no earlier warning. Also warns before an
 * actual browser navigation/close with unsaved changes
 * (`beforeunload`) -- deliberately *not* attempting to also block
 * in-app React Router navigation (e.g. clicking "Back to queue"
 * mid-edit), since that needs a specific react-router API this project
 * hasn't verified against a real build yet, unlike the router usage
 * already proven out elsewhere in this app. Narrower but confirmed
 * correct beats broader but guessed at.
 */
export function EditProposalPanel({ proposalId, proposal }: { proposalId: number; proposal: MappingProposal }) {
  const navigate = useNavigate();
  const [editing, setEditing] = useState(false);
  const original = useMemo(() => JSON.stringify(proposal, null, 2), [proposal]);
  const [draft, setDraft] = useState(original);
  const [submitting, setSubmitting] = useState(false);
  const [errors, setErrors] = useState<string[] | null>(null);

  const dirty = draft !== original;
  const jsonError = useMemo(() => {
    try {
      JSON.parse(draft);
      return null;
    } catch {
      return "Not valid JSON — check for a missing comma or bracket.";
    }
  }, [draft]);

  useEffect(() => {
    if (!editing || !dirty) return;
    const handler = (e: BeforeUnloadEvent) => {
      e.preventDefault();
      e.returnValue = "";
    };
    window.addEventListener("beforeunload", handler);
    return () => window.removeEventListener("beforeunload", handler);
  }, [editing, dirty]);

  if (!editing) {
    return (
      <button className={styles.toggleButton} onClick={() => setEditing(true)}>
        Edit mapping
      </button>
    );
  }

  async function handleSave() {
    setErrors(null);
    if (jsonError) {
      setErrors([jsonError]);
      return;
    }
    const parsed = JSON.parse(draft) as MappingProposal;

    setSubmitting(true);
    try {
      const response = await amendProposal(proposalId, parsed);
      navigate(`/proposals/${response.mappingProposalId}`);
    } catch (err) {
      setErrors(err instanceof ApiError ? (err.problems.length > 0 ? err.problems : [err.message]) : ["Couldn't reach the backend."]);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className={styles.panel}>
      <div className={styles.hint}>
        Editing here replaces this proposal with a new one containing whatever's below — this one moves to
        "Edited (superseded)" and isn't approvable anymore, matching how rejecting works.
      </div>
      <textarea className={styles.textarea} value={draft} onChange={(e) => setDraft(e.target.value)} spellCheck={false} />
      {dirty && jsonError && <div className={styles.inlineWarning}>{jsonError}</div>}
      <div className={styles.buttonRow}>
        <button className={styles.saveButton} onClick={handleSave} disabled={submitting || !!jsonError}>
          {submitting ? "Saving…" : "Save as new proposal"}
        </button>
        <button className={styles.cancelButton} disabled={submitting || !dirty} onClick={() => setDraft(original)}>
          Reset changes
        </button>
        <button
          className={styles.cancelButton}
          disabled={submitting}
          onClick={() => {
            if (dirty && !window.confirm("Discard your changes?")) return;
            setEditing(false);
            setDraft(original);
            setErrors(null);
          }}
        >
          Cancel
        </button>
      </div>
      {errors && (
        <div className={styles.errorBox}>
          Couldn't save:
          <ul>
            {errors.map((e, i) => (
              <li key={i}>{e}</li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}
