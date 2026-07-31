import type { FieldMapping, MappingProposal } from "../api/types";
import { ConfidenceBar } from "./ConfidenceBar";
import styles from "./FieldMappingTable.module.css";

function SourceCell({ mapping }: { mapping: FieldMapping }) {
  return (
    <div>
      {mapping.sourceColumn && (
        <div>
          <div className={styles.sourceKind}>Column</div>
          <div className={styles.sourceValue}>{mapping.sourceColumn}</div>
        </div>
      )}
      {mapping.sourceConstant && (
        <div>
          <div className={styles.sourceKind}>Constant</div>
          <div className={styles.sourceValue}>{mapping.sourceConstant}</div>
        </div>
      )}

      {mapping.selectedVariant && (
        <div className={styles.variantMap}>
          Every row is <strong>{mapping.selectedVariant}</strong>
        </div>
      )}
      {mapping.variantValueMap && Object.keys(mapping.variantValueMap).length > 0 && (
        <div className={styles.variantMap}>
          {Object.entries(mapping.variantValueMap).map(([from, to]) => (
            <div key={from} className={styles.variantMapRow}>
              <span>"{from}"</span>
              <span>→</span>
              <span>{to}</span>
            </div>
          ))}
        </div>
      )}

      {mapping.transformations.map((t, i) => (
        <span key={i} className={styles.transformBadge}>
          {t.type} × {t.multiplier}
        </span>
      ))}

      {mapping.conversionNotes && <div className={styles.notes}>{mapping.conversionNotes}</div>}
    </div>
  );
}

export function FieldMappingTable({ proposal }: { proposal: MappingProposal }) {
  return (
    <div>
      <div className={styles.table}>
        <div className={styles.headerRow}>
          <span>Canonical field</span>
          <span>Proposed source</span>
          <span>Confidence</span>
        </div>
        {proposal.fieldMappings.map((mapping) => (
          <div key={mapping.canonicalFieldPath} className={styles.row}>
            <span className={styles.fieldPath}>{mapping.canonicalFieldPath}</span>
            <SourceCell mapping={mapping} />
            <ConfidenceBar value={mapping.confidence} />
          </div>
        ))}
      </div>

      {proposal.unmappedSourceColumns.length > 0 && (
        <div className={styles.unmapped}>
          <strong>{proposal.unmappedSourceColumns.length}</strong> source column
          {proposal.unmappedSourceColumns.length === 1 ? "" : "s"} left unmapped:
          <div className={styles.unmappedList}>{proposal.unmappedSourceColumns.join(", ")}</div>
        </div>
      )}
    </div>
  );
}
