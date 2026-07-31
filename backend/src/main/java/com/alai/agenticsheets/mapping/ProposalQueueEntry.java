package com.alai.agenticsheets.mapping;

import java.time.OffsetDateTime;

/**
 * One row for the Step 8 review queue -- {@link StoredMappingProposal}
 * alone isn't enough to build a usable queue view from: it has no
 * client, filename, or model information, only foreign-key IDs. A
 * reviewer deciding what to look at next needs to see what each
 * proposal is actually for, not a bare proposal ID.
 */
public record ProposalQueueEntry(
        long id,
        long importBatchId,
        String status,
        String modelId,
        String clientId,
        String sourceFilename,
        String worksheet,
        OffsetDateTime createdAt) {
}
