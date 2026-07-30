package com.alai.agenticsheets.mapping;

import java.time.OffsetDateTime;

/** One {@code delivery_log} row. */
public record DeliveryLogEntry(
        long id,
        long importBatchId,
        long mappingProposalId,
        int attemptNumber,
        String transport,
        String outcome,
        Integer statusCode,
        String errorMessage,
        OffsetDateTime attemptedAt) {
}
