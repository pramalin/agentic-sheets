import { useState } from "react";
import { ApiError, approveProposal, getStoredReviewedBy, rejectProposal, setStoredReviewedBy } from "../api/client";
import type { ApproveResponse, StoredMappingProposal } from "../api/types";
import styles from "./ReviewActions.module.css";

export function ReviewActions({
  proposal,
  onDecided,
}: {
  proposal: StoredMappingProposal;
  onDecided: () => void;
}) {
  const [reviewedBy, setReviewedBy] = useState(getStoredReviewedBy());
  const [showReasonInput, setShowReasonInput] = useState(false);
  const [reason, setReason] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<ApproveResponse | null>(null);

  if (proposal.status !== "PENDING") {
    return (
      <div className={styles.panel}>
        <p className={styles.decidedNote}>
          {proposal.status === "REJECTED"
            ? `Rejected${proposal.rejectionReason ? ` — ${proposal.rejectionReason}` : "."}`
            : "This proposal has already been decided — no further action needed here."}
        </p>
      </div>
    );
  }

  function rememberReviewer(name: string) {
    setReviewedBy(name);
    setStoredReviewedBy(name);
  }

  async function handleApprove() {
    setSubmitting(true);
    setError(null);
    try {
      const response = await approveProposal(proposal.id, reviewedBy || "reviewer");
      setResult(response);
      onDecided();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Couldn't reach the backend.");
    } finally {
      setSubmitting(false);
    }
  }

  async function handleReject() {
    setSubmitting(true);
    setError(null);
    try {
      await rejectProposal(proposal.id, reviewedBy || "reviewer", reason);
      onDecided();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Couldn't reach the backend.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className={styles.panel}>
      <div className={styles.reviewerRow}>
        <label htmlFor="reviewedBy">Reviewed by</label>
        <input
          id="reviewedBy"
          className={styles.reviewerInput}
          value={reviewedBy}
          onChange={(e) => rememberReviewer(e.target.value)}
          placeholder="your name"
        />
      </div>

      {showReasonInput && (
        <input
          className={styles.reasonInput}
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          placeholder="Reason for rejecting (optional, but helps whoever re-proposes)"
          autoFocus
        />
      )}

      <div className={styles.buttonRow}>
        <button className={styles.approveButton} onClick={handleApprove} disabled={submitting}>
          {submitting ? "Approving…" : "Approve"}
        </button>
        <button
          className={styles.rejectButton}
          disabled={submitting}
          onClick={() => (showReasonInput ? handleReject() : setShowReasonInput(true))}
        >
          {showReasonInput ? (submitting ? "Rejecting…" : "Confirm reject") : "Reject"}
        </button>
      </div>

      {error && <div className={styles.errorBox}>{error}</div>}

      {result && (
        <div className={styles.resultBox}>
          Approved. {result.validation.validRows.length} row(s) valid, {result.validation.rowErrors.length} row error(s).
          {result.dispatch && (
            <>
              {" "}
              Dispatch: <strong>{result.dispatch.outcome}</strong> ({result.dispatch.attempts} attempt
              {result.dispatch.attempts === 1 ? "" : "s"}
              {result.dispatch.lastStatusCode ? `, HTTP ${result.dispatch.lastStatusCode}` : ""}) — {result.dispatch.message}
            </>
          )}
        </div>
      )}
    </div>
  );
}
