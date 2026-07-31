import type { DeliveryLogEntry, ValidationRun } from "../api/types";
import styles from "./HistoryPanel.module.css";

function formatTimestamp(iso: string): string {
  return new Date(iso).toLocaleString();
}

export function ValidationHistory({ runs }: { runs: ValidationRun[] }) {
  if (runs.length === 0) {
    return <div className={styles.panel}>
      <div className={styles.empty}>No validation attempts yet — happens automatically on approve or redeliver.</div>
    </div>;
  }

  return (
    <div className={styles.panel}>
      {runs.map((run) => (
        <div key={run.id} className={styles.entry}>
          <div className={styles.entryHead}>
            <span className={run.invalidRowCount > 0 ? styles.outcomeDanger : styles.outcomeSuccess}>
              {run.validRowCount} valid, {run.invalidRowCount} invalid
            </span>
            <span className={styles.timestamp}>{formatTimestamp(run.createdAt)}</span>
          </div>
          {run.rowErrors.length > 0 && (
            <ul className={styles.rowErrorList}>
              {run.rowErrors.map((e) => (
                <li key={e.rowIndex}>
                  Row {e.rowIndex}: {e.problems.join("; ")}
                </li>
              ))}
            </ul>
          )}
        </div>
      ))}
    </div>
  );
}

export function DeliveryHistory({ entries }: { entries: DeliveryLogEntry[] }) {
  if (entries.length === 0) {
    return <div className={styles.panel}>
      <div className={styles.empty}>No delivery attempts yet.</div>
    </div>;
  }

  return (
    <div className={styles.panel}>
      {entries.map((entry) => {
        const tone = entry.outcome === "SUCCESS" ? styles.outcomeSuccess
          : entry.outcome === "CONFIGURATION_ERROR" || entry.outcome === "NOT_IMPLEMENTED" ? styles.outcomeNeutral
          : styles.outcomeDanger;
        return (
          <div key={entry.id} className={styles.entry}>
            <div className={styles.entryHead}>
              <span className={tone}>
                Attempt {entry.attemptNumber} — {entry.outcome}
                {entry.statusCode ? ` (HTTP ${entry.statusCode})` : ""}
              </span>
              <span className={styles.timestamp}>{formatTimestamp(entry.attemptedAt)}</span>
            </div>
            <div className={styles.detail}>
              {entry.transport}
              {entry.errorMessage ? ` — ${entry.errorMessage}` : ""}
            </div>
          </div>
        );
      })}
    </div>
  );
}
