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

  // Deliberately checked before the "already decided" early return below,
  // not nested inside it -- a real race a live browser test caught: after
  // a successful approve, this component sets `result` locally *and*
  // calls `onDecided()`, which triggers the parent's re-fetch. Once that
  // re-fetch resolves, `proposal.status` becomes "APPROVED" and this
  // component re-renders with the new prop -- previously hitting the
  // early return before ever checking `result`, discarding the success
  // message the instant the parent's refresh landed. Whether that
  // happened before or after a person even saw it came down to pure
  // timing, not anything the UI was doing on purpose -- a real reviewer
  // could hit the exact same flash-and-vanish depending on network
  // speed, not just test automation. Rendering it unconditionally here
  // means the outcome of *this* review action stays visible for the
  // rest of the page's life, regardless of which branch below fires.
  const resultBanner = result && (
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
  );

  if (proposal.status !== "PENDING") {
    return (
      <div className={styles.panel}>
        <p className={styles.decidedNote}>
          {proposal.status === "REJECTED"
            ? `Rejected${proposal.rejectionReason ? ` — ${proposal.rejectionReason}` : "."}`
            : "This proposal has already been decided — no further action needed here."}
        </p>
        {resultBanner}
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
        {!showReasonInput && (
          <button className={styles.approveButton} onClick={handleApprove} disabled={submitting}>
            {submitting ? "Approving…" : "Approve"}
          </button>
        )}
        <button
          className={styles.rejectButton}
          disabled={submitting}
          onClick={() => (showReasonInput ? handleReject() : setShowReasonInput(true))}
        >
          {showReasonInput ? (submitting ? "Rejecting…" : "Confirm reject") : "Reject"}
        </button>
        {showReasonInput && (
          <button
            className={styles.rejectButton}
            disabled={submitting}
            onClick={() => {
              setShowReasonInput(false);
              setReason("");
            }}
          >
            Cancel
          </button>
        )}
      </div>

      {error && <div className={styles.errorBox}>{error}</div>}

      {resultBanner}
    </div>
  );
}
