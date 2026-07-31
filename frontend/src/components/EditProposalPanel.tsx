import { useState } from "react";
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
 */
export function EditProposalPanel({ proposalId, proposal }: { proposalId: number; proposal: MappingProposal }) {
  const navigate = useNavigate();
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(() => JSON.stringify(proposal, null, 2));
  const [submitting, setSubmitting] = useState(false);
  const [errors, setErrors] = useState<string[] | null>(null);

  if (!editing) {
    return (
      <button className={styles.toggleButton} onClick={() => setEditing(true)}>
        Edit mapping
      </button>
    );
  }

  async function handleSave() {
    setErrors(null);
    let parsed: MappingProposal;
    try {
      parsed = JSON.parse(draft);
    } catch {
      setErrors(["That's not valid JSON — check for a missing comma or bracket."]);
      return;
    }

    setSubmitting(true);
    try {
      const response = await amendProposal(proposalId, parsed);
      navigate(`/proposals/${response.mappingProposalId}`);
    } catch (err) {
      setErrors(err instanceof ApiError ? err.problems.length > 0 ? err.problems : [err.message] : ["Couldn't reach the backend."]);
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
      <div className={styles.buttonRow}>
        <button className={styles.saveButton} onClick={handleSave} disabled={submitting}>
          {submitting ? "Saving…" : "Save as new proposal"}
        </button>
        <button className={styles.cancelButton} onClick={() => setEditing(false)} disabled={submitting}>
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
