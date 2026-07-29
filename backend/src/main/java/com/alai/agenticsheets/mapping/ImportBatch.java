package com.alai.agenticsheets.mapping;

/** One {@code import_batch} row. */
public record ImportBatch(
        long id,
        String modelId,
        String clientId,
        String sourceFilename,
        String contentHash,
        String worksheet,
        int configVersion,
        String status) {
}
