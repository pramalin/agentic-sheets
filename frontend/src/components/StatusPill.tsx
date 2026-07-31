import type { Status } from "../api/types";
import styles from "./StatusPill.module.css";

type Tone = "pending" | "info" | "success" | "danger" | "neutral";

/**
 * Maps every known status (across both mapping_proposal.status and
 * import_batch.status) to a tone and a plain-language label. New
 * statuses should be added here explicitly rather than falling through
 * to the default -- a status this component doesn't recognize is worth
 * surfacing visibly (as "neutral" with its raw name) rather than
 * silently mis-categorizing it as success or failure.
 */
const STATUS_INFO: Record<Status, { tone: Tone; label: string }> = {
  PENDING: { tone: "pending", label: "Needs review" },
  PROPOSING: { tone: "pending", label: "Proposing" },
  PROPOSING_ERROR: { tone: "danger", label: "Proposing failed" },
  APPROVED: { tone: "info", label: "Approved" },
  REJECTED: { tone: "neutral", label: "Rejected" },
  SUPERSEDED: { tone: "neutral", label: "Edited (superseded)" },
  PROCESSING: { tone: "info", label: "Delivering" },
  VALIDATION_FAILED: { tone: "danger", label: "Validation failed" },
  PROCESSING_ERROR: { tone: "danger", label: "Processing error" },
  SOURCE_CHANGED: { tone: "pending", label: "Source changed" },
  CONFIG_CHANGED: { tone: "pending", label: "Config changed" },
  DELIVERED: { tone: "success", label: "Delivered" },
  DELIVERY_FAILED: { tone: "danger", label: "Delivery failed" },
};

export function StatusPill({ status }: { status: string }) {
  const info = STATUS_INFO[status as Status] ?? { tone: "neutral" as Tone, label: status };
  return (
    <span className={`${styles.pill} ${styles[info.tone]}`}>
      <span className={styles.dot} aria-hidden="true" />
      {info.label}
    </span>
  );
}
