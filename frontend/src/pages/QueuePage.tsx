import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { listProposals, ApiError } from "../api/client";
import type { ProposalQueueEntry } from "../api/types";
import { StatusPill } from "../components/StatusPill";
import { relativeTime } from "../utils/format";
import styles from "./QueuePage.module.css";

const FILTERS: { label: string; status: string | undefined }[] = [
  { label: "Needs review", status: "PENDING" },
  { label: "All", status: undefined },
];

export function QueuePage() {
  const [filter, setFilter] = useState<string | undefined>("PENDING");
  const [entries, setEntries] = useState<ProposalQueueEntry[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setEntries(null);
    setError(null);
    listProposals(filter)
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
              className={filter === f.status ? styles.filterButtonActive : styles.filterButton}
              onClick={() => setFilter(f.status)}
            >
              {f.label}
            </button>
          ))}
        </div>
      </div>

      <div className={styles.list}>
        <div className={styles.rowHeader}>
          <span>Status</span>
          <span>Client</span>
          <span>File</span>
          <span>Model</span>
          <span style={{ textAlign: "right" }}>Proposed</span>
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
              {filter === "PENDING" ? "Nothing waiting for review" : "No proposals yet"}
            </div>
            {filter === "PENDING"
              ? "Every proposed mapping has been approved or rejected. New spreadsheets will show up here once proposed."
              : "Proposals appear here once a spreadsheet has been submitted for mapping."}
          </div>
        )}

        {!error &&
          entries !== null &&
          entries.map((entry) => (
            <Link key={entry.id} to={`/proposals/${entry.id}`} className={styles.row}>
              <StatusPill status={entry.status} />
              <span className={styles.client}>{entry.clientId}</span>
              <span className={styles.file} title={`${entry.sourceFilename} · ${entry.worksheet}`}>
                {entry.sourceFilename} · {entry.worksheet}
              </span>
              <span className={styles.model}>{entry.modelId}</span>
              <span className={styles.created}>{relativeTime(entry.createdAt)}</span>
            </Link>
          ))}
      </div>
    </div>
  );
}
