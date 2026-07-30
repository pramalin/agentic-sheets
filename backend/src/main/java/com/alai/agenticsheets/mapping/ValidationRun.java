package com.alai.agenticsheets.mapping;

import java.time.OffsetDateTime;
import java.util.List;

/** One {@code validation_run} row -- a durable record of what a
  * {@code validate()} call actually found, so a reviewer looking at a
  * proposal later can see what happened, not just the batch's current
  * one-word status. */
public record ValidationRun(
        long id,
        long importBatchId,
        long mappingProposalId,
        int validRowCount,
        int invalidRowCount,
        List<ValidationReport.RowError> rowErrors,
        OffsetDateTime createdAt) {
}
