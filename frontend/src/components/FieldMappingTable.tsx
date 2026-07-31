import type { FieldMapping, MappingProposal, SourceColumn } from "../api/types";
import { ConfidenceBar } from "./ConfidenceBar";
import styles from "./FieldMappingTable.module.css";

/** Keyed by column header for quick lookup while rendering each row --
  * built once by the caller from /internal/explore/table's response. */
export type SourceColumnLookup = Record<string, SourceColumn>;

function SampleValues({ column }: { column: SourceColumn | undefined }) {
  if (!column || column.sampleValues.length === 0) return null;
  return (
    <div className={styles.samples}>
      <span className={styles.samplesLabel}>{column.inferredType.toLowerCase()}, e.g.</span>{" "}
      {column.sampleValues.slice(0, 3).join(", ")}
    </div>
  );
}

function SourceCell({ mapping, sourceColumns }: { mapping: FieldMapping; sourceColumns: SourceColumnLookup }) {
  return (
    <div>
      {mapping.sourceColumn && (
        <div>
          <div className={styles.sourceKind}>Column</div>
          <div className={styles.sourceValue}>{mapping.sourceColumn}</div>
          <SampleValues column={sourceColumns[mapping.sourceColumn]} />
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

export function FieldMappingTable({
  proposal,
  sourceColumns = {},
}: {
  proposal: MappingProposal;
  /** Optional -- degrades to no sample values shown if the source-table
    * fetch failed or hasn't completed yet, rather than blocking the
    * whole table on it. */
  sourceColumns?: SourceColumnLookup;
}) {
  return (
    <div>
      {/* This is a CSS grid, not a semantic <table> -- restructuring to
        * one would also mean reworking the layout (table layout and
        * grid layout don't mix cleanly), a bigger change than an
        * external review's finding here warranted. ARIA table/row/cell
        * roles give a screen reader the same column-association
        * information a real <table> would, without that rework. */}
      <div className={styles.table} role="table" aria-label="Proposed field mappings">
        <div className={styles.headerRow} role="row">
          <span role="columnheader">Canonical field</span>
          <span role="columnheader">Proposed source</span>
          <span role="columnheader">Confidence</span>
        </div>
        {proposal.fieldMappings.map((mapping) => (
          <div key={mapping.canonicalFieldPath} className={styles.row} role="row">
            <span className={styles.fieldPath} role="cell">{mapping.canonicalFieldPath}</span>
            <span role="cell">
              <SourceCell mapping={mapping} sourceColumns={sourceColumns} />
            </span>
            <span role="cell">
              <ConfidenceBar value={mapping.confidence} />
            </span>
          </div>
        ))}
      </div>

      {proposal.unmappedSourceColumns.length > 0 && (
        <div className={styles.unmapped}>
          <strong>{proposal.unmappedSourceColumns.length}</strong> source column
          {proposal.unmappedSourceColumns.length === 1 ? "" : "s"} left unmapped:
          <div className={styles.unmappedList}>
            {proposal.unmappedSourceColumns.map((header) => (
              <div key={header} className={styles.unmappedRow}>
                <span className={styles.sourceValue}>{header}</span>
                <SampleValues column={sourceColumns[header]} />
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
