import { useCallback, useEffect, useMemo, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { ApiError, describeTable, getProposalDetail, redeliverProposal } from "../api/client";
import type { ApproveResponse, ProposalDetail } from "../api/types";
import { StatusPill } from "../components/StatusPill";
import { FieldMappingTable, type SourceColumnLookup } from "../components/FieldMappingTable";
import { ReviewActions } from "../components/ReviewActions";
import { EditProposalPanel } from "../components/EditProposalPanel";
import { ValidationHistory, DeliveryHistory } from "../components/HistoryPanel";
import styles from "./ProposalDetailPage.module.css";

const REDELIVERABLE_BATCH_STATUSES = new Set(["DELIVERY_FAILED", "PROCESSING_ERROR"]);
const LOW_CONFIDENCE_THRESHOLD = 0.6;

export function ProposalDetailPage() {
  const { id } = useParams<{ id: string }>();
  const [detail, setDetail] = useState<ProposalDetail | null>(null);
  // Separate error states for separate causes -- an external review
  // correctly caught that a single shared `error` meant a failed
  // redelivery attempt could show "Couldn't load proposal" even though
  // the proposal was already loaded and still visible on screen.
  const [loadError, setLoadError] = useState<string | null>(null);
  const [redeliveryError, setRedeliveryError] = useState<string | null>(null);
  const [sampleError, setSampleError] = useState(false);
  const [redelivering, setRedelivering] = useState(false);
  const [redeliverResult, setRedeliverResult] = useState<ApproveResponse | null>(null);
  const [sourceColumns, setSourceColumns] = useState<SourceColumnLookup>({});

  const load = useCallback(() => {
    if (!id) return;
    setLoadError(null);
    getProposalDetail(Number(id))
      .then((result) => {
        setDetail(result);
        // Independent of the main fetch, deliberately -- a failure
        // here shouldn't block the review screen, only the
        // sample-values enhancement. But an external review correctly
        // caught that silently converting the failure into an empty
        // lookup made a genuine "couldn't load evidence" outcome look
        // identical to "this proposal has no source samples" -- the
        // reviewer had no way to tell the two apart. sampleError makes
        // that distinction visible instead of hiding it.
        setSampleError(false);
        describeTable(result.batch.sourceFilename, result.batch.worksheet)
          .then((table) => {
            const lookup: SourceColumnLookup = {};
            for (const col of table.columns) lookup[col.header] = col;
            setSourceColumns(lookup);
          })
          .catch(() => setSampleError(true));
      })
      .catch((err: unknown) => setLoadError(err instanceof ApiError ? err.message : "Couldn't reach the backend."));
  }, [id]);

  useEffect(() => {
    setDetail(null);
    setSourceColumns({});
    setSampleError(false);
    setRedeliverResult(null);
    setRedeliveryError(null);
    load();
  }, [load]);

  async function handleRedeliver() {
    if (!id) return;
    setRedelivering(true);
    setRedeliveryError(null);
    try {
      const result = await redeliverProposal(Number(id));
      setRedeliverResult(result);
      load();
    } catch (err) {
      setRedeliveryError(err instanceof ApiError ? err.message : "Couldn't reach the backend.");
    } finally {
      setRedelivering(false);
    }
  }

  const summary = useMemo(() => {
    if (!detail) return null;
    const mappings = detail.proposal.proposal.fieldMappings;
    const lowConfidence = mappings.filter((m) => m.confidence < LOW_CONFIDENCE_THRESHOLD).length;
    const unmapped = detail.proposal.proposal.unmappedSourceColumns.length;
    return { total: mappings.length, lowConfidence, unmapped };
  }, [detail]);

  return (
    <div className={styles.page}>
      <Link to="/" className={styles.backLink}>
        ← Back to queue
      </Link>

      {loadError && (
        <p className={`${styles.centered} ${styles.errorState}`}>Couldn't load proposal {id}: {loadError}</p>
      )}
      {!loadError && !detail && <p className={styles.centered}>Loading…</p>}

      {detail && (
        <>
          <div className={styles.header}>
            <h1 className={styles.title}>Proposal {detail.proposal.id}</h1>
            <span className={styles.statusLabel}>
              Decision: <StatusPill status={detail.proposal.status} />
            </span>
            {detail.batch.status !== detail.proposal.status && (
              <span className={styles.statusLabel}>
                Delivery: <StatusPill status={detail.batch.status} />
              </span>
            )}
          </div>
          <div className={styles.meta}>
            <strong>{detail.batch.clientId}</strong> · <code>{detail.batch.sourceFilename}</code> ·{" "}
            {detail.batch.worksheet} · model {detail.batch.modelId}
          </div>

          {detail.proposal.proposal.summary && <div className={styles.summary}>{detail.proposal.proposal.summary}</div>}

          {sampleError && (
            <div className={styles.sampleWarning}>
              Source samples could not be loaded. You can still review the proposed mapping below, but the
              source-value evidence normally shown next to each field is unavailable right now.
              <button className={styles.retryLink} onClick={load}>
                Retry
              </button>
            </div>
          )}

          <div className={styles.section}>
            <div className={styles.sectionTitle}>Proposed mapping</div>
            <FieldMappingTable proposal={detail.proposal.proposal} sourceColumns={sourceColumns} />
          </div>

          <div className={styles.section}>
            <div className={styles.sectionTitle}>Decision</div>
            {summary && (
              <div className={styles.riskSummary}>
                {summary.total} field{summary.total === 1 ? "" : "s"} mapped
                {summary.lowConfidence > 0 && (
                  <span className={styles.riskFlag}>
                    {" "}
                    · {summary.lowConfidence} below {Math.round(LOW_CONFIDENCE_THRESHOLD * 100)}% confidence
                  </span>
                )}
                {summary.unmapped > 0 && (
                  <span className={styles.riskFlag}>
                    {" "}
                    · {summary.unmapped} source column{summary.unmapped === 1 ? "" : "s"} unmapped
                  </span>
                )}
                {sampleError && <span className={styles.riskFlag}> · samples unavailable</span>}
              </div>
            )}
            {detail.proposal.status === "APPROVED" && REDELIVERABLE_BATCH_STATUSES.has(detail.batch.status) && (
              <div className={styles.redeliverBar}>
                Delivery didn't go through ({detail.batch.status}) — approved decision still stands.
                <button className={styles.redeliverButton} onClick={handleRedeliver} disabled={redelivering}>
                  {redelivering ? "Retrying…" : "Retry delivery"}
                </button>
              </div>
            )}
            {redeliveryError && (
              <div className={styles.redeliverBar} style={{ color: "var(--accent-danger)" }}>
                Retry failed: {redeliveryError}
              </div>
            )}
            {redeliverResult?.dispatch && (
              <div className={styles.redeliverBar} style={{ marginBottom: "var(--space-3)" }}>
                Retry result: <strong>{redeliverResult.dispatch.outcome}</strong> — {redeliverResult.dispatch.message}
              </div>
            )}
            {detail.proposal.status === "PENDING" && (
              <EditProposalPanel proposalId={detail.proposal.id} proposal={detail.proposal.proposal} />
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
