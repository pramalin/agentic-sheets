import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { listProposals, ApiError } from "../api/client";
import type { ProposalQueueEntry } from "../api/types";
import { StatusPill } from "../components/StatusPill";
import { relativeTime } from "../utils/format";
import styles from "./QueuePage.module.css";

/** Statuses that mean "something needs a person's attention" -- distinct
  * from PENDING ("needs a first decision"): these are proposals or
  * batches that already had a decision or attempt and didn't land
  * cleanly. An external review correctly pointed out this is likely
  * more operationally useful than "All" once a queue has real history
  * in it, since it surfaces exactly what a person can actually act on
  * (retry, re-propose, or investigate) rather than everything ever
  * decided. */
const NEEDS_ATTENTION_STATUSES = [
  "PROPOSING_ERROR",
  "VALIDATION_FAILED",
  "PROCESSING_ERROR",
  "DELIVERY_FAILED",
  "SOURCE_CHANGED",
  "CONFIG_CHANGED",
];

const FILTERS: { label: string; statuses: string[] | undefined }[] = [
  { label: "Needs review", statuses: ["PENDING"] },
  { label: "Needs attention", statuses: NEEDS_ATTENTION_STATUSES },
  { label: "All", statuses: undefined },
];

export function QueuePage() {
  const [filter, setFilter] = useState(FILTERS[0]);
  const [entries, setEntries] = useState<ProposalQueueEntry[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setEntries(null);
    setError(null);
    listProposals(filter.statuses)
      .then((result) => {
        if (!cancelled) setEntries(result);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setError(err instanceof ApiError ? err.message : "Couldn't reach the backend.");
      });
    return () => {
      cancelled = true;
    };
  }, [filter]);

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <div>
          <h1 className={styles.title}>Review queue</h1>
          <div className={styles.subtitle}>Mapping proposals from client spreadsheets, most recent first</div>
        </div>
        <div className={styles.filters}>
          {FILTERS.map((f) => (
            <button
              key={f.label}
              className={filter.label === f.label ? styles.filterButtonActive : styles.filterButton}
              onClick={() => setFilter(f)}
            >
              {f.label}
            </button>
          ))}
        </div>
      </div>

      <div className={styles.list} role="table" aria-label="Review queue">
        <div className={styles.rowHeader} role="row">
          <span role="columnheader">Status</span>
          <span role="columnheader">Client</span>
          <span role="columnheader">File</span>
          <span role="columnheader">Model</span>
          <span role="columnheader" style={{ textAlign: "right" }}>Proposed</span>
        </div>

        {error && (
          <div className={styles.errorState}>
            Couldn't load the queue: {error}
            {error.toLowerCase().includes("missing or invalid authorization") && (
              <div style={{ marginTop: 8, fontSize: 13 }}>
                Check the API key in Settings — it may be missing or no longer valid.
              </div>
            )}
          </div>
        )}

        {!error && entries === null && <div className={styles.loadingState}>Loading…</div>}

        {!error && entries !== null && entries.length === 0 && (
          <div className={styles.emptyState}>
            <div className={styles.emptyStateTitle}>
              {filter.label === "Needs review"
                ? "Nothing waiting for review"
                : filter.label === "Needs attention"
                  ? "Nothing needs attention"
                  : "No proposals yet"}
            </div>
            {filter.label === "Needs review"
              ? "Every proposed mapping has been approved or rejected. New spreadsheets will show up here once proposed."
              : filter.label === "Needs attention"
                ? "No failed validations, failed deliveries, or drift-related states right now."
                : "Proposals appear here once a spreadsheet has been submitted for mapping."}
          </div>
        )}

        {!error &&
          entries !== null &&
          entries.map((entry) => (
            <Link key={entry.id} to={`/proposals/${entry.id}`} className={styles.row} role="row">
              <span role="cell">
                <StatusPill status={entry.status} />
              </span>
              <span role="cell" className={styles.client}>{entry.clientId}</span>
              <span role="cell" className={styles.file} title={`${entry.sourceFilename} · ${entry.worksheet}`}>
                {entry.sourceFilename} · {entry.worksheet}
              </span>
              <span role="cell" className={styles.model}>{entry.modelId}</span>
              <span role="cell" className={styles.created}>{relativeTime(entry.createdAt)}</span>
            </Link>
          ))}
      </div>
    </div>
  );
}
