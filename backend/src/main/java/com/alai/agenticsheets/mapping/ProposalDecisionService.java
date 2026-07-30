package com.alai.agenticsheets.mapping;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * Wraps the proposal-claim and batch-claim into one real database
 * transaction for the operations that need both to succeed or neither
 * to -- {@code /approve}, {@code /reject}, and completing
 * {@code /propose} (saving the new proposal and releasing the batch
 * back to {@code PENDING}).
 *
 * This exists because a sixth-round external review proved, with a
 * concrete interleaving, that the previous reasoning for *not* needing
 * this was wrong: claiming the proposal (removing it from PENDING) and
 * claiming the batch were two separate, sequential statements with a
 * real window between them, and {@code /propose} could claim the batch
 * in exactly that window -- leaving a proposal permanently
 * {@code APPROVED} with a batch that was never actually processed, no
 * different from the bug Step 7.4 was supposed to have fixed. See
 * {@code mapping-notes.md}'s Step 7.5 notes for the earlier, incorrect
 * reasoning this replaces, and why it was wrong.
 *
 * Deliberately narrow: only the status-transition pairs here are
 * transactional. Validation, MCP calls, and HTTP delivery all still
 * happen afterward, entirely outside any transaction, in {@code
 * MappingController.processDelivery} -- holding a database connection
 * (and its locks) for however long a network call takes would be a
 * substantially worse problem than the one this class exists to solve.
 */
@Service
public class ProposalDecisionService {

    private final MappingProposalRepository mappingProposalRepository;
    private final ImportBatchRepository importBatchRepository;

    public ProposalDecisionService(MappingProposalRepository mappingProposalRepository,
            ImportBatchRepository importBatchRepository) {
        this.mappingProposalRepository = mappingProposalRepository;
        this.importBatchRepository = importBatchRepository;
    }

    /**
     * Proposal PENDING -> APPROVED and batch PENDING -> PROCESSING,
     * atomically. Throwing (rather than returning a boolean) on either
     * failure is deliberate -- Spring's default transaction behavior
     * only rolls back on an unchecked exception propagating out of the
     * method, not on a normal return value, so a failure here has to
     * throw or the proposal claim would stay committed even though the
     * batch claim failed, which is exactly the bug this class exists to
     * prevent.
     */
    @Transactional
    public void claimForApproval(long proposalId, long importBatchId, String reviewedBy) {
        if (!mappingProposalRepository.claim(proposalId, reviewedBy)) {
            throw new IllegalStateException(
                    "proposal " + proposalId + " could not be claimed -- not PENDING (already approved, possibly "
                            + "by a concurrent request, or never PENDING to begin with)");
        }
        if (!importBatchRepository.claimForProcessing(importBatchId, Set.of("PENDING"), "PROCESSING")) {
            throw new IllegalStateException(
                    "batch " + importBatchId + " could not be claimed -- not PENDING (a concurrent /propose most "
                            + "likely claimed it first); the proposal claim above is rolled back along with this");
        }
    }

    /** Same idea for rejection: proposal PENDING -> REJECTED and batch
      * PENDING -> REJECTED, atomically -- closes the analogous race
      * where a concurrent {@code /propose} could claim the batch as
      * PROPOSING in the window between what used to be two separate
      * statements. */
    @Transactional
    public void claimForRejection(long proposalId, long importBatchId, String reviewedBy, String reason) {
        if (!mappingProposalRepository.reject(proposalId, reviewedBy, reason)) {
            throw new IllegalStateException(
                    "proposal " + proposalId + " could not be rejected -- not PENDING");
        }
        if (!importBatchRepository.claimForProcessing(importBatchId, Set.of("PENDING"), "REJECTED")) {
            throw new IllegalStateException(
                    "batch " + importBatchId + " could not be updated to REJECTED -- not PENDING (a concurrent "
                            + "/propose most likely claimed it first); the proposal rejection above is rolled back "
                            + "along with this");
        }
    }

    /**
     * Saves a newly-generated proposal and releases the batch back to
     * PENDING, atomically. Without this, a crash (or any failure)
     * between the insert and the release could leave a PENDING proposal
     * attached to a batch stuck at PROPOSING forever -- and worse,
     * {@code /propose}'s fast path (return the existing PENDING proposal
     * without a model call) would keep returning that same
     * never-approvable proposal indefinitely, since it only checks for
     * a pending proposal's existence, not whether the batch agrees.
     * Wrapping both in one transaction makes that inconsistent
     * combination unreachable going forward, rather than something a
     * caller has to separately detect and repair.
     */
    @Transactional
    public long saveProposalAndReleaseBatch(long importBatchId, int configVersion, MappingProposal proposal) {
        long proposalId = mappingProposalRepository.save(importBatchId, configVersion, proposal);
        if (!importBatchRepository.updateStatusIfCurrent(importBatchId, "PROPOSING", "PENDING")) {
            throw new IllegalStateException(
                    "batch " + importBatchId + " was not PROPOSING when releasing it after a successful proposal "
                            + "-- should be unreachable given the batch was claimed into PROPOSING immediately "
                            + "before the model call; rolling back the proposal insert rather than leaving an "
                            + "orphaned proposal attached to a batch in an unexpected state");
        }
        return proposalId;
    }
}
