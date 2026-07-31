package com.alai.agenticsheets.inbox;

import java.time.LocalDate;

/**
 * One row in {@code inbox_file} -- see that table's own schema comment
 * for why this is a deliberately separate identity from
 * {@link com.alai.agenticsheets.mapping.ImportBatch}, not a duplicate of
 * it.
 */
public record InboxFile(
        long id,
        String logicalFilename,
        String contentHash,
        String originalPath,
        String currentPath,
        String feedType,
        String clientId,
        LocalDate sourceDate,
        String worksheet,
        String status,
        Long importBatchId,
        int attemptCount,
        String lastError) {
}
