package com.alai.agenticsheets.mapping;

/** The result of a successful (or already-existing) proposal --
  * extracted to a top-level record as of Step 9, since
  * {@link MappingWorkflowService} needs it too, not just
  * {@link MappingController}. */
public record ProposeResponse(long importBatchId, long mappingProposalId, MappingProposal proposal) {
}
