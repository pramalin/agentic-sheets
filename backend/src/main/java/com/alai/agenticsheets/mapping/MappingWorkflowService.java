package com.alai.agenticsheets.mapping;

import com.alai.agenticsheets.canonical.CanonicalModel;
import com.alai.agenticsheets.canonical.CanonicalModelRegistry;
import com.alai.agenticsheets.canonical.ClientConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.Set;

/**
 * Proposal-creation orchestration -- hashing, batch creation, claims,
 * resolution (agent call or memory reuse), and persistence -- extracted
 * out of {@link MappingController} as of Step 9. {@link MappingResolutionService}
 * (Step 10) is what actually decides whether a model call happens at
 * all; this class is everything *around* that decision, not the
 * decision itself.
 *
 * Two entry points, deliberately different business operations sharing
 * most of their implementation, not one method serving both:
 *
 * <ul>
 * <li>{@link #proposeManually} -- the existing, unchanged behavior:
 * a human (or a script acting on a human's behalf) explicitly asking for
 * a proposal, including re-proposing after {@code REJECTED} or a
 * recorded failure. That breadth is deliberate human-initiated recovery
 * (see {@code mapping-notes.md}'s Step 7.4 section) -- appropriate for
 * something a person specifically asked for, never appropriate for
 * something a background process decided on its own.</li>
 * <li>{@link #proposeInitialFromInbox} -- Step 9's scanner calls this,
 * and only this. It creates the very first proposal for a batch that has
 * never had one, and nothing else -- no re-proposing a rejected batch
 * merely because its source file is still sitting in the inbox. An
 * external review of Step 9's design was the one that caught this
 * distinction needing to exist as two separate operations at all, not a
 * single method with two callers.</li>
 * </ul>
 */
@Service
public class MappingWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(MappingWorkflowService.class);

    /** Batch states safe to start a *new* proposal from, for a human
      * explicitly asking. See mapping-notes.md's Step 7.4 section for
      * the full reasoning. */
    private static final Set<String> ELIGIBLE_FOR_MANUAL_PROPOSE =
            Set.of("PENDING", "REJECTED", "VALIDATION_FAILED", "SOURCE_CHANGED", "CONFIG_CHANGED", "PROPOSING_ERROR");

    /** The scanner may only ever claim a batch that has never been
      * proposed at all -- {@code findOrCreate} only ever returns a batch
      * at PENDING with zero proposals in that state (a genuinely brand
      * new batch, or one from a crashed attempt that never made it past
      * the claim -- see {@link #proposeInitialFromInbox}'s own
      * javadoc). Deliberately excludes every recovery status
      * {@link #ELIGIBLE_FOR_MANUAL_PROPOSE} includes -- those are all
      * "a human decided to try again," never something the scanner
      * should decide on a person's behalf. */
    private static final Set<String> ELIGIBLE_FOR_INBOX_PROPOSE = Set.of("PENDING");

    private final MappingResolutionService resolutionService;
    private final ImportBatchRepository importBatchRepository;
    private final MappingProposalRepository mappingProposalRepository;
    private final FileHasher fileHasher;
    private final CanonicalModelRegistry registry;
    private final ProposalDecisionService proposalDecisionService;

    public MappingWorkflowService(
            MappingResolutionService resolutionService,
            ImportBatchRepository importBatchRepository,
            MappingProposalRepository mappingProposalRepository,
            FileHasher fileHasher,
            CanonicalModelRegistry registry,
            ProposalDecisionService proposalDecisionService) {
        this.resolutionService = resolutionService;
        this.importBatchRepository = importBatchRepository;
        this.mappingProposalRepository = mappingProposalRepository;
        this.fileHasher = fileHasher;
        this.registry = registry;
        this.proposalDecisionService = proposalDecisionService;
    }

    /**
     * Creates or reuses an import_batch, then either returns the
     * existing PENDING proposal for it (fast path, no model call) or
     * claims the batch into {@code PROPOSING} *before* calling the
     * model. Unchanged behavior from before Step 9's extraction -- see
     * this class's own javadoc for why its eligibility is deliberately
     * broader than {@link #proposeInitialFromInbox}'s.
     */
    public ProposeResponse proposeManually(String modelId, String clientId, String path, String worksheet) {
        CanonicalModel model = registry.get(modelId);
        ClientConfig client = registry.getClient(clientId);

        String contentHash = fileHasher.sha256(path);
        long batchId = importBatchRepository.findOrCreate(
                model.modelId(), clientId, path, contentHash, worksheet, model.version());

        Optional<StoredMappingProposal> existingPending = mappingProposalRepository.findPendingByBatchId(batchId);
        if (existingPending.isPresent()) {
            StoredMappingProposal existing = existingPending.get();
            return new ProposeResponse(batchId, existing.id(), existing.proposal());
        }

        return claimAndPropose(batchId, model, client, path, worksheet, ELIGIBLE_FOR_MANUAL_PROPOSE);
    }

    /**
     * Step 9's scanner calls this, and only this -- never
     * {@link #proposeManually}. Two safety layers, deliberately
     * redundant: {@code inbox_file}'s own atomic claim (see
     * {@code InboxFileRepository#claimForProcessing}) is what actually
     * prevents two scan cycles or two scanner instances from racing each
     * other for the same physical file, so by the time this method is
     * called, the caller has already established it's the sole owner of
     * this attempt. This method's own {@code existsForBatch} check is a
     * second, independent guard against a different scenario entirely:
     * a human already manually proposed this exact file (same filename,
     * hash, worksheet, model, client, and config version) before the
     * scanner got to it. In that case this returns whatever already
     * exists -- any status, not just PENDING -- without ever calling the
     * model a second time for work a person already did.
     *
     * Only once neither of those applies does this claim the batch (from
     * PENDING only -- see {@link #ELIGIBLE_FOR_INBOX_PROPOSE}) and
     * actually propose.
     */
    public ProposeResponse proposeInitialFromInbox(String modelId, String clientId, String path, String worksheet) {
        CanonicalModel model = registry.get(modelId);
        ClientConfig client = registry.getClient(clientId);

        String contentHash = fileHasher.sha256(path);
        long batchId = importBatchRepository.findOrCreate(
                model.modelId(), clientId, path, contentHash, worksheet, model.version());

        if (mappingProposalRepository.existsForBatch(batchId)) {
            // Prefer the pending one if there is one; otherwise link to
            // whatever the batch's most recent proposal was -- either
            // way, the scanner just needs *a* real proposal id to point
            // inbox_file at, not a fresh model call for work a human
            // already did.
            Optional<StoredMappingProposal> pending = mappingProposalRepository.findPendingByBatchId(batchId);
            StoredMappingProposal existing = pending.orElseGet(() ->
                    mappingProposalRepository.findMostRecentByBatchId(batchId)
                            .orElseThrow(() -> new IllegalStateException(
                                    "batch " + batchId + " reported existsForBatch=true but no proposal was found "
                                            + "-- should be unreachable")));
            return new ProposeResponse(batchId, existing.id(), existing.proposal());
        }

        return claimAndPropose(batchId, model, client, path, worksheet, ELIGIBLE_FOR_INBOX_PROPOSE);
    }

    private ProposeResponse claimAndPropose(
            long batchId, CanonicalModel model, ClientConfig client, String path, String worksheet,
            Set<String> eligibleFromStatuses) {
        if (!importBatchRepository.claimForProcessing(batchId, eligibleFromStatuses, "PROPOSING")) {
            ImportBatch current = importBatchRepository.findById(batchId);
            throw new IllegalStateException(
                    "batch " + batchId + " could not be claimed for proposing -- current status is "
                            + current.status() + ", not one of " + eligibleFromStatuses
                            + " (already being processed concurrently, already delivered, or a state that needs "
                            + "/redeliver instead of a fresh proposal)");
        }

        ResolvedProposal resolved;
        try {
            resolved = resolutionService.resolve(model, client, path, worksheet);
        } catch (RuntimeException e) {
            log.error("propose (resolution) failed unexpectedly for batch {}", batchId, e);
            importBatchRepository.updateStatusIfCurrent(batchId, "PROPOSING", "PROPOSING_ERROR");
            throw e;
        }

        long proposalId;
        try {
            proposalId = proposalDecisionService.saveProposalAndReleaseBatch(
                    batchId, model.version(), resolved.proposal(), resolved.origin(), resolved.mappingMemoryId(),
                    resolved.columnFingerprint(), resolved.clientConfigFingerprint());
        } catch (RuntimeException e) {
            log.error("propose (persist+release) failed unexpectedly for batch {}", batchId, e);
            importBatchRepository.updateStatusIfCurrent(batchId, "PROPOSING", "PROPOSING_ERROR");
            throw e;
        }

        return new ProposeResponse(batchId, proposalId, resolved.proposal());
    }
}
