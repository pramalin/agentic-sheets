/**
 * Types matching the backend's JSON shapes exactly -- verified against
 * the real Java records in backend/src/main/java/com/alai/agenticsheets/mapping/
 * (MappingProposal, StoredMappingProposal, ImportBatch, ValidationRun,
 * DeliveryLogEntry, ValidationReport.RowError) rather than guessed at.
 * Jackson serializes a record's own component names directly, so these
 * field names match the Java declarations one for one.
 */

export interface TransformationStep {
  type: string;
  multiplier: string;
}

export interface FieldMapping {
  canonicalFieldPath: string;
  sourceColumn: string | null;
  sourceConstant: string | null;
  selectedVariant: string | null;
  variantValueMap: Record<string, string> | null;
  transformations: TransformationStep[];
  confidence: number;
  conversionNotes: string | null;
}

export interface MappingProposal {
  fieldMappings: FieldMapping[];
  unmappedSourceColumns: string[];
  summary: string;
}

/**
 * The full set of statuses a proposal or batch can be in, across both
 * mapping_proposal.status and import_batch.status -- these are two
 * separate columns with overlapping but not identical value sets (see
 * db/init/01-orchestration-schema.sql's informal status comments), so
 * this union is deliberately the superset rather than trying to model
 * them as two distinct types the UI would need to reconcile everywhere
 * it displays "the" status.
 */
export type Status =
  | "PENDING"
  | "PROPOSING"
  | "PROPOSING_ERROR"
  | "APPROVED"
  | "REJECTED"
  | "SUPERSEDED"
  | "PROCESSING"
  | "VALIDATION_FAILED"
  | "PROCESSING_ERROR"
  | "SOURCE_CHANGED"
  | "CONFIG_CHANGED"
  | "DELIVERED"
  | "DELIVERY_FAILED";

export interface StoredMappingProposal {
  id: number;
  importBatchId: number;
  configVersion: number;
  proposal: MappingProposal;
  status: Status;
  rejectionReason: string | null;
}

export interface ImportBatch {
  id: number;
  modelId: string;
  clientId: string;
  sourceFilename: string;
  contentHash: string;
  worksheet: string;
  configVersion: number;
  status: Status;
}

export interface RowError {
  rowIndex: number;
  problems: string[];
}

export interface ValidationRun {
  id: number;
  importBatchId: number;
  mappingProposalId: number;
  validRowCount: number;
  invalidRowCount: number;
  rowErrors: RowError[];
  createdAt: string;
}

export interface DeliveryLogEntry {
  id: number;
  importBatchId: number;
  mappingProposalId: number;
  attemptNumber: number;
  transport: string;
  outcome: string;
  statusCode: number | null;
  errorMessage: string | null;
  attemptedAt: string;
}

export interface ProposalDetail {
  proposal: StoredMappingProposal;
  batch: ImportBatch;
  validationRuns: ValidationRun[];
  deliveryLog: DeliveryLogEntry[];
}

/**
 * One row of GET /internal/mapping/proposals -- joined with import_batch
 * on the backend so the queue can show what a proposal is actually for
 * (client, file, worksheet), not just a bare proposal ID. Matches
 * ProposalQueueEntry.java exactly.
 */
export interface ProposalQueueEntry {
  id: number;
  importBatchId: number;
  status: Status;
  modelId: string;
  clientId: string;
  sourceFilename: string;
  worksheet: string;
  createdAt: string;
}

/** MappingController.ProposeResponse -- also what /amend returns. */
export interface ProposeResponse {
  importBatchId: number;
  mappingProposalId: number;
  proposal: MappingProposal;
}

/**
 * GET /internal/explore/table's response shape -- verified against a
 * real response rather than guessed (see ui-notes.md's Step 8b section
 * for why this was deferred until it could be). sampleValues are always
 * strings in the real response, even for NUMBER/DATE columns -- the
 * MCP tool stringifies everything, so this type reflects that rather
 * than a more "correct"-looking union that doesn't match reality.
 */
export interface SourceColumn {
  header: string;
  inferredType: string;
  nullRate: number;
  sampleValues: string[];
}

export interface DescribeTableResponse {
  worksheet: string;
  headerRowIndex: number;
  firstDataRowIndex: number;
  lastDataRowIndex: number;
  detectionConfidence: number;
  columns: SourceColumn[];
}

export interface DispatchResult {
  outcome: "SUCCESS" | "TERMINAL_FAILURE" | "RETRIES_EXHAUSTED" | "NOT_IMPLEMENTED" | "CONFIGURATION_ERROR" | "INTERRUPTED";
  attempts: number;
  lastStatusCode: number | null;
  message: string;
}

/** MappingController.ValidationSummary. */
export interface ValidationSummary {
  validRows: unknown[];
  rowErrors: RowError[];
}

/** MappingController.ApproveResponse -- also the shape /redeliver
  * returns. */
export interface ApproveResponse {
  importBatchId: number;
  mappingProposalId: number;
  validation: ValidationSummary;
  dispatch: DispatchResult | null;
}

/** The shape of every /internal/** error response, per
  * MappingController.ValidationErrorResponse. */
export interface ApiErrorBody {
  problems: string[];
}
