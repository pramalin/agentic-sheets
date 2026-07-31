import styles from "./ConfidenceBar.module.css";

/**
 * A visual bar for the agent's per-field confidence score, colored
 * along the same tone scale as StatusPill (low confidence reads as a
 * "needs a closer look" orange, not an alarming red -- a proposal is
 * expected to have some lower-confidence fields, that's exactly why a
 * human reviews it, not a sign something's broken). An external review
 * correctly caught that this comment and the actual implementation had
 * drifted apart -- anything below 60% previously rendered in
 * `--accent-danger` (red), the same color a genuine validation or
 * delivery failure uses, undermining the exact distinction this
 * comment describes. Fixed to actually stay in the orange family at
 * every confidence level; red is reserved for real failures elsewhere
 * in the app, never for a confidence score by itself.
 */
export function ConfidenceBar({ value }: { value: number }) {
  const pct = Math.round(Math.max(0, Math.min(1, value)) * 100);
  const color = pct >= 85 ? "var(--accent-success)" : pct >= 60 ? "var(--accent-pending)" : "var(--accent-pending-strong)";

  return (
    <div className={styles.wrap}>
      <div className={styles.track}>
        <div className={styles.fill} style={{ width: `${pct}%`, background: color }} />
      </div>
      <span className={styles.label}>{pct}%</span>
    </div>
  );
}
