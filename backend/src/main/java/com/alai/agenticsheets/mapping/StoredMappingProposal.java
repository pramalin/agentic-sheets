package com.alai.agenticsheets.mapping;

/** One {@code mapping_proposal} row, with the {@code jsonb} column
  * already decoded back into a {@link MappingProposal}. */
public record StoredMappingProposal(
        long id,
        long importBatchId,
        int configVersion,
        MappingProposal proposal,
        String status,
        String rejectionReason,
        String origin,
        Long mappingMemoryId,
        String columnFingerprint,
        String clientConfigFingerprint) {
}
