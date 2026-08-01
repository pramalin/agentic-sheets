package com.alai.agenticsheets.mapping;

/**
 * What {@link MappingResolutionService#resolve} produces -- the
 * proposal itself, provenance a reviewer should be able to see (did a
 * real model call just happen, or was this reused from a prior
 * clean-validated approval with the same structure), and the
 * fingerprints that produced this result -- carried forward so
 * approval-time memory promotion (see {@link MappingMemoryService})
 * can read them back rather than recomputing via a second
 * {@code describe_table} call.
 */
public record ResolvedProposal(
        MappingProposal proposal, String origin, Long mappingMemoryId,
        String columnFingerprint, String clientConfigFingerprint) {

    public static final String ORIGIN_AGENT = "AGENT";
    public static final String ORIGIN_MEMORY = "MEMORY";
    // Not actually produced by resolve() -- amendProposal in
    // ProposalDecisionService sets this directly. Kept here anyway as
    // the one place all three of mapping_proposal.origin's possible
    // values are defined, rather than let a second copy of the string
    // drift out of sync with this one.
    public static final String ORIGIN_HUMAN_AMENDMENT = "HUMAN_AMENDMENT";

    public static ResolvedProposal fromAgent(MappingProposal proposal, String columnFingerprint,
            String clientConfigFingerprint) {
        return new ResolvedProposal(proposal, ORIGIN_AGENT, null, columnFingerprint, clientConfigFingerprint);
    }

    public static ResolvedProposal fromMemory(MappingProposal proposal, long mappingMemoryId,
            String columnFingerprint, String clientConfigFingerprint) {
        return new ResolvedProposal(
                proposal, ORIGIN_MEMORY, mappingMemoryId, columnFingerprint, clientConfigFingerprint);
    }
}
