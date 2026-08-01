package com.alai.agenticsheets.mapping;

/**
 * One row in {@code mapping_memory} -- see that table's own schema
 * comment for the full reasoning (three external review rounds,
 * summarized there and in {@code mapping-notes.md}'s Step 10 section).
 */
public record MappingMemory(
        long id,
        String clientId,
        String worksheet,
        String modelId,
        int modelVersion,
        String clientConfigFingerprint,
        String columnFingerprint,
        MappingProposal proposal,
        long sourceProposalId,
        String status,
        String invalidationReason) {
}
