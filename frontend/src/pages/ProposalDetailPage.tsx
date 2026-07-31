import { useCallback, useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { ApiError, getProposalDetail, redeliverProposal } from "../api/client";
import type { ApproveResponse, ProposalDetail } from "../api/types";
import { StatusPill } from "../components/StatusPill";
import { FieldMappingTable } from "../components/FieldMappingTable";
import { ReviewActions } from "../components/ReviewActions";
import { ValidationHistory, DeliveryHistory } from "../components/HistoryPanel";
import styles from "./ProposalDetailPage.module.css";

const REDELIVERABLE_BATCH_STATUSES = new Set(["DELIVERY_FAILED", "PROCESSING_ERROR"]);

export function ProposalDetailPage() {
  const { id } = useParams<{ id: string }>();
  const [detail, setDetail] = useState<ProposalDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [redelivering, setRedelivering] = useState(false);
  const [redeliverResult, setRedeliverResult] = useState<ApproveResponse | null>(null);

  const load = useCallback(() => {
    if (!id) return;
    setError(null);
    getProposalDetail(Number(id))
      .then(setDetail)
      .catch((err: unknown) => setError(err instanceof ApiError ? err.message : "Couldn't reach the backend."));
  }, [id]);

  useEffect(() => {
    setDetail(null);
    setRedeliverResult(null);
    load();
  }, [load]);

  async function handleRedeliver() {
    if (!id) return;
    setRedelivering(true);
    try {
      const result = await redeliverProposal(Number(id));
      setRedeliverResult(result);
      load();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Couldn't reach the backend.");
    } finally {
      setRedelivering(false);
    }
  }

  return (
    <div className={styles.page}>
      <Link to="/" className={styles.backLink}>
        ← Back to queue
      </Link>

      {error && <p className={`${styles.centered} ${styles.errorState}`}>Couldn't load proposal {id}: {error}</p>}
      {!error && !detail && <p className={styles.centered}>Loading…</p>}

      {detail && (
        <>
          <div className={styles.header}>
            <h1 className={styles.title}>Proposal {detail.proposal.id}</h1>
            <StatusPill status={detail.proposal.status} />
            {detail.batch.status !== detail.proposal.status && <StatusPill status={detail.batch.status} />}
          </div>
          <div className={styles.meta}>
            <strong>{detail.batch.clientId}</strong> · <code>{detail.batch.sourceFilename}</code> ·{" "}
            {detail.batch.worksheet} · model {detail.batch.modelId}
          </div>

          {detail.proposal.proposal.summary && <div className={styles.summary}>{detail.proposal.proposal.summary}</div>}

          <div className={styles.section}>
            <div className={styles.sectionTitle}>Proposed mapping</div>
            <FieldMappingTable proposal={detail.proposal.proposal} />
          </div>

          <div className={styles.section}>
            <div className={styles.sectionTitle}>Decision</div>
            {detail.proposal.status === "APPROVED" && REDELIVERABLE_BATCH_STATUSES.has(detail.batch.status) && (
              <div className={styles.redeliverBar}>
                Delivery didn't go through ({detail.batch.status}) — approved decision still stands.
                <button className={styles.redeliverButton} onClick={handleRedeliver} disabled={redelivering}>
                  {redelivering ? "Retrying…" : "Retry delivery"}
                </button>
              </div>
            )}
            {redeliverResult?.dispatch && (
              <div className={styles.redeliverBar} style={{ marginBottom: "var(--space-3)" }}>
                Retry result: <strong>{redeliverResult.dispatch.outcome}</strong> — {redeliverResult.dispatch.message}
              </div>
            )}
            <ReviewActions proposal={detail.proposal} onDecided={load} />
          </div>

          <div className={styles.section}>
            <div className={styles.sectionTitle}>Validation history</div>
            <ValidationHistory runs={detail.validationRuns} />
          </div>

          <div className={styles.section}>
            <div className={styles.sectionTitle}>Delivery history</div>
            <DeliveryHistory entries={detail.deliveryLog} />
          </div>
        </>
      )}
    </div>
  );
}
