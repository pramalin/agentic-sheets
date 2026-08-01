package com.alai.agenticsheets.mapping;

import com.alai.agenticsheets.canonical.CanonicalModel;
import com.alai.agenticsheets.canonical.CanonicalModelRegistry;
import com.alai.agenticsheets.canonical.ClientConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Step 6's manual trigger for the mapping pipeline, and Step 7's manual
 * trigger for approval -- creates or reuses an import_batch, asks the
 * agent to propose a mapping, persists it pending review
 * ({@code /propose}), and, once a human decides to approve it
 * ({@code /proposals/{id}/approve}), runs the deterministic validator
 * and dispatcher.
 *
 * A sixth-round external review correctly refuted this class's own
 * previous reasoning: {@code /approve} and {@code /reject} each touch
 * *two* rows (the proposal, then the batch) as two separate,
 * independently-committing statements, and no amount of each statement
 * being individually atomic closes the window between them -- a
 * concurrent {@code /propose} can claim the batch in exactly that gap.
 * Fixed by delegating those two-row transitions to
 * {@link ProposalDecisionService}, which wraps each pair in one real
 * database transaction -- see that class's javadoc and
 * {@code mapping-notes.md}'s Step 7.5 section for the full account,
 * including the confirmed counter-example that proved the earlier
 * version wrong.
 */
@RestController
@RequestMapping("/internal/mapping")
public class MappingController {

    private static final Logger log = LoggerFactory.getLogger(MappingController.class);

    /** Retrying after a recorded failure, or recovering a batch stuck at
      * APPROVED by a genuine process crash -- deliberately excludes
      * DELIVERED and PROCESSING/PROPOSING. Deliberately disjoint from
      * {@link MappingWorkflowService}'s own eligible-for-propose sets --
      * no status is eligible for both a fresh proposal and a
      * redelivery, which is what lets {@code /redeliver} use a single,
      * untransacted claim safely: no concurrent {@code /propose} could
      * ever compete for a batch in one of these states. */
    private static final Set<String> ELIGIBLE_FOR_REDELIVER =
            Set.of("APPROVED", "DELIVERY_FAILED", "PROCESSING_ERROR");

    private final AgentMappingProposalService proposalService;
    private final MappingWorkflowService workflowService;
    private final ImportBatchRepository importBatchRepository;
    private final MappingProposalRepository mappingProposalRepository;
    private final ValidationRunRepository validationRunRepository;
    private final DeliveryLogRepository deliveryLogRepository;
    private final FileHasher fileHasher;
    private final CanonicalModelRegistry registry;
    private final ProposalValidationService validationService;
    private final Dispatcher dispatcher;
    private final ProposalDecisionService proposalDecisionService;
    private final MappingMemoryService mappingMemoryService;

    public MappingController(
            AgentMappingProposalService proposalService,
            MappingWorkflowService workflowService,
            ImportBatchRepository importBatchRepository,
            MappingProposalRepository mappingProposalRepository,
            ValidationRunRepository validationRunRepository,
            DeliveryLogRepository deliveryLogRepository,
            FileHasher fileHasher,
            CanonicalModelRegistry registry,
            ProposalValidationService validationService,
            Dispatcher dispatcher,
            ProposalDecisionService proposalDecisionService,
            MappingMemoryService mappingMemoryService) {
        this.proposalService = proposalService;
        this.workflowService = workflowService;
        this.importBatchRepository = importBatchRepository;
        this.mappingProposalRepository = mappingProposalRepository;
        this.validationRunRepository = validationRunRepository;
        this.deliveryLogRepository = deliveryLogRepository;
        this.fileHasher = fileHasher;
        this.registry = registry;
        this.validationService = validationService;
        this.dispatcher = dispatcher;
        this.proposalDecisionService = proposalDecisionService;
        this.mappingMemoryService = mappingMemoryService;
    }

    /**
     * Creates or reuses an import_batch, then either returns the
     * existing PENDING proposal for it (fast path, no model call) or
     * claims the batch into {@code PROPOSING} *before* calling the
     * model. As of Step 9, this is a thin HTTP-translation layer over
     * {@link MappingWorkflowService#proposeManually} -- the orchestration
     * itself (hashing, batch creation, claims, model invocation,
     * persistence) moved there, alongside the new
     * {@code proposeInitialFromInbox} the scanner uses instead. See that
     * class's own javadoc for why those are two different methods with
     * different eligibility rules, not one shared by both callers.
     */
    @PostMapping("/propose")
    public ProposeResponse propose(
            @RequestParam String modelId,
            @RequestParam String clientId,
            @RequestParam String path,
            @RequestParam String worksheet) {
        return workflowService.proposeManually(modelId, clientId, path, worksheet);
    }

    /**
     * A human edits a pending proposal rather than approving or
     * rejecting it outright -- the "edit" verb in "approve/edit/reject",
     * the one piece of the original Step 8 scope that had no backend
     * support until now (see {@code ui-notes.md}'s Step 8b section for
     * why it was deferred, and {@code mapping-notes.md}'s Step 7.4
     * section for where {@code SUPERSEDED} was first floated as a
     * future need before it was actually built). The edited content is
     * validated the same way agent output is -- a real field path, a
     * real observed source column, a valid variant -- before the old
     * proposal is superseded and the new one persisted, both atomically
     * via {@link ProposalDecisionService#amendProposal}.
     */
    @PostMapping("/proposals/{id}/amend")
    public ProposeResponse amend(@PathVariable long id, @RequestBody MappingProposal editedProposal) {
        StoredMappingProposal stored = mappingProposalRepository.findById(id);
        ImportBatch batch = importBatchRepository.findById(stored.importBatchId());
        CanonicalModel model = registry.get(batch.modelId());

        proposalService.validateEdited(editedProposal, model, batch.sourceFilename(), batch.worksheet());

        long newProposalId = proposalDecisionService.amendProposal(
                id, stored.importBatchId(), model.version(), editedProposal,
                stored.columnFingerprint(), stored.clientConfigFingerprint());
        return new ProposeResponse(stored.importBatchId(), newProposalId, editedProposal);
    }

    /**
     * The Step 8 review queue -- most recent first, joined with batch
     * context (client, file, worksheet) so it's actually usable, not
     * just a list of proposal IDs. {@code status} can be given multiple
     * times (or comma-separated -- Spring splits either form into this
     * list automatically) to narrow to any of several statuses at once,
     * which a single-status filter couldn't express for "needs
     * attention" (several distinct failure/drift statuses, not one).
     * Omit entirely for a broader history view.
     */
    @GetMapping("/proposals")
    public List<ProposalQueueEntry> list(
            @RequestParam(required = false) List<String> status,
            @RequestParam(defaultValue = "50") int limit) {
        return mappingProposalRepository.findQueueEntries(status, limit);
    }

    /**
     * Everything a review screen needs about one proposal, scoped
     * correctly to that proposal alone (not the whole batch's history --
     * see {@link DeliveryLogRepository#findByProposalId}).
     */
    @GetMapping("/proposals/{id}")
    public ProposalDetail detail(@PathVariable long id) {
        StoredMappingProposal proposal = mappingProposalRepository.findById(id);
        ImportBatch batch = importBatchRepository.findById(proposal.importBatchId());
        List<ValidationRun> validationRuns = validationRunRepository.findByProposalId(id);
        List<DeliveryLogEntry> deliveryLog = deliveryLogRepository.findByProposalId(id);
        return new ProposalDetail(proposal, batch, validationRuns, deliveryLog);
    }

    /**
     * Approves a pending proposal and claims its batch for processing --
     * both in one transaction, via
     * {@link ProposalDecisionService#claimForApproval}, not two
     * separate statements. A sixth-round external review provided a
     * concrete counter-example proving a two-statement version wrong: a
     * concurrent {@code /propose} could claim the batch into
     * {@code PROPOSING} in the window between "proposal claimed as
     * APPROVED" and "batch claimed as PROCESSING," leaving the proposal
     * permanently APPROVED with no path forward. Both claims now
     * succeed or roll back together.
     */
    @PostMapping("/proposals/{id}/approve")
    public ApproveResponse approve(
            @PathVariable long id,
            @RequestParam(defaultValue = "manual-api-call") String reviewedBy) {
        StoredMappingProposal stored = mappingProposalRepository.findById(id);
        proposalDecisionService.claimForApproval(id, stored.importBatchId(), reviewedBy);

        // The transaction above already committed both claims -- the
        // batch is already PROCESSING, doValidateAndDispatch does not
        // (and must not) try to claim it again.
        StoredMappingProposal claimed = mappingProposalRepository.findById(id);
        return doValidateAndDispatch(id, claimed);
    }

    /**
     * The other terminal decision a human can make about a pending
     * proposal -- same reasoning as {@code /approve}: the proposal
     * reject and the batch update now happen in one transaction via
     * {@link ProposalDecisionService#claimForRejection}, closing the
     * same class of race there too.
     */
    @PostMapping("/proposals/{id}/reject")
    public void reject(
            @PathVariable long id,
            @RequestParam(defaultValue = "manual-api-call") String reviewedBy,
            @RequestParam(required = false) String reason) {
        StoredMappingProposal stored = mappingProposalRepository.findById(id);
        proposalDecisionService.claimForRejection(id, stored.importBatchId(), reviewedBy, reason);
        mappingMemoryService.invalidateIfMemoryDerived(stored,
                "Rejected by " + reviewedBy + (reason != null ? ": " + reason : ""));
    }

    /**
     * Re-runs validation and dispatch for an already-approved proposal.
     * A single, untransacted claim is genuinely sufficient here (unlike
     * {@code /approve} and {@code /reject}): {@code /redeliver} never
     * changes the proposal's own status (it's already permanently
     * APPROVED), so there's only one row's worth of state to claim, and
     * {@link #ELIGIBLE_FOR_REDELIVER} is deliberately disjoint from both
     * of {@link MappingWorkflowService}'s eligible-for-propose sets --
     * no concurrent {@code /propose} could ever be racing for a batch in
     * one of these states.
     */
    @PostMapping("/proposals/{id}/redeliver")
    public ApproveResponse redeliver(@PathVariable long id) {
        StoredMappingProposal stored = mappingProposalRepository.findById(id);
        if (!"APPROVED".equals(stored.status())) {
            throw new IllegalStateException(
                    "proposal " + id + " is not APPROVED (status: " + stored.status()
                            + ") -- only an already-approved proposal can be redelivered");
        }
        if (!importBatchRepository.claimForProcessing(stored.importBatchId(), ELIGIBLE_FOR_REDELIVER, "PROCESSING")) {
            ImportBatch current = importBatchRepository.findById(stored.importBatchId());
            throw new IllegalStateException(
                    "batch " + stored.importBatchId() + " could not be claimed for processing -- current status is "
                            + current.status() + ", not one of " + ELIGIBLE_FOR_REDELIVER);
        }
        return doValidateAndDispatch(id, stored);
    }

    /**
     * BREAK-GLASS OPERATION -- not an ordinary review-screen action.
     * Confirm the previous process is actually gone before calling
     * this, never as a first response to slowness; it cannot tell the
     * difference between "genuinely dead" and "merely slow," and
     * calling it against a merely-slow request recreates the exact
     * concurrent-delivery race the atomic claims exist to prevent.
     *
     * Handles a batch stuck in either {@code PROCESSING} or
     * {@code PROPOSING} -- the two exclusive states a crash could leave
     * a batch in. For {@code PROCESSING}, recovery is always
     * {@code PROCESSING_ERROR}. For {@code PROPOSING}, recovery depends
     * on whether a proposal actually got saved before the crash: if a
     * {@code PENDING} proposal already exists for this batch, nothing
     * was lost and the batch just needs to go back to {@code PENDING};
     * if not, the whole attempt failed and the batch becomes
     * {@code PROPOSING_ERROR} so a fresh {@code /propose} can be tried.
     */
    @PostMapping("/batches/{id}/recover-stuck")
    public void recoverStuck(@PathVariable long id) {
        ImportBatch current = importBatchRepository.findById(id);
        String targetStatus = switch (current.status()) {
            case "PROCESSING" -> "PROCESSING_ERROR";
            case "PROPOSING" -> mappingProposalRepository.findPendingByBatchId(id).isPresent() ? "PENDING" : "PROPOSING_ERROR";
            default -> throw new IllegalStateException(
                    "batch " + id + " is not currently PROCESSING or PROPOSING (status: " + current.status()
                            + ") -- nothing to recover");
        };
        if (!importBatchRepository.updateStatusIfCurrent(id, current.status(), targetStatus)) {
            throw new IllegalStateException(
                    "batch " + id + " status changed concurrently while attempting recovery -- retry if still stuck");
        }
    }

    /**
     * Validates and dispatches an approved proposal whose batch has
     * *already* been claimed into {@code PROCESSING} by the caller
     * ({@code approve} claims it transactionally via
     * {@link ProposalDecisionService}; {@code redeliver} claims it
     * directly beforehand) -- this method does not claim anything
     * itself, deliberately, so it can't accidentally attempt a second,
     * redundant claim on top of one that already succeeded.
     */
    private ApproveResponse doValidateAndDispatch(long proposalId, StoredMappingProposal stored) {
        long batchId = stored.importBatchId();
        try {
            ImportBatch batch = importBatchRepository.findById(batchId);

            CanonicalModel currentModel = registry.get(batch.modelId());
            if (currentModel.version() != stored.configVersion()) {
                importBatchRepository.updateStatus(batch.id(), "CONFIG_CHANGED");
                throw new IllegalStateException(
                        "canonical model '" + batch.modelId() + "' has moved from version " + stored.configVersion()
                                + " (when this proposal was created) to version " + currentModel.version()
                                + " -- refusing to validate against a config that may no longer match what was "
                                + "proposed. Re-run /propose against the current config before approving.");
            }

            String hashBeforeReading = fileHasher.sha256(batch.sourceFilename());
            if (!hashBeforeReading.equals(batch.contentHash())) {
                importBatchRepository.updateStatus(batch.id(), "SOURCE_CHANGED");
                throw new IllegalStateException(
                        "SOURCE_CHANGED: the file '" + batch.sourceFilename() + "' has different content now than "
                                + "when this batch was created (expected hash " + batch.contentHash()
                                + ", observed " + hashBeforeReading + ") -- refusing to approve a mapping against "
                                + "data that isn't what was actually reviewed. Re-run /propose against the current "
                                + "file.");
            }

            ClientConfig client = registry.getClient(batch.clientId());

            ValidationReport validationReport = validationService.validate(currentModel, client, batch, stored.proposal());
            validationRunRepository.save(batch.id(), proposalId, validationReport);

            String hashAfterReading = fileHasher.sha256(batch.sourceFilename());
            if (!hashAfterReading.equals(hashBeforeReading)) {
                importBatchRepository.updateStatus(batch.id(), "SOURCE_CHANGED");
                throw new IllegalStateException(
                        "SOURCE_CHANGED: the file '" + batch.sourceFilename() + "' changed while its rows were "
                                + "being read -- refusing to dispatch data that may be an inconsistent mix of two "
                                + "versions of the file. Re-run /propose against the current file.");
            }

            if (validationReport.validRows().isEmpty()) {
                importBatchRepository.updateStatus(batch.id(), "VALIDATION_FAILED");
                return new ApproveResponse(batch.id(), proposalId, ValidationSummary.from(validationReport), null);
            }

            // Delivery success is deliberately not a condition here -- a
            // network failure below says nothing about whether the
            // mapping itself was correct. Zero row errors is the actual
            // bar; see MappingMemoryService's own javadoc.
            mappingMemoryService.promoteIfEligible(stored, batch, currentModel, validationReport);

            DispatchResult dispatchResult = dispatcher.dispatch(
                    batch.id(), proposalId, currentModel.target(), validationReport.validRows());
            String finalStatus = dispatchResult.outcome() == DispatchResult.Outcome.SUCCESS
                    ? "DELIVERED" : "DELIVERY_FAILED";
            importBatchRepository.updateStatus(batch.id(), finalStatus);

            return new ApproveResponse(batch.id(), proposalId, ValidationSummary.from(validationReport), dispatchResult);
        } catch (RuntimeException e) {
            log.error("validate-and-dispatch failed unexpectedly for proposal {} (batch {})", proposalId, batchId, e);
            importBatchRepository.updateStatusIfCurrent(batchId, "PROCESSING", "PROCESSING_ERROR");
            throw e;
        }
    }

    @ExceptionHandler(MappingProposalValidationException.class)
    public ResponseEntity<ValidationErrorResponse> handleValidationFailure(MappingProposalValidationException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ValidationErrorResponse(e.problems()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ValidationErrorResponse> handleIllegalState(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ValidationErrorResponse(List.of(e.getMessage())));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ValidationErrorResponse> handleUnexpected(Exception e) {
        log.error("unhandled exception in MappingController", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ValidationErrorResponse(List.of(
                        e.getClass().getSimpleName() + ": " + e.getMessage())));
    }

    public record ApproveResponse(long importBatchId, long mappingProposalId, ValidationSummary validation,
            DispatchResult dispatch) {
    }

    public record ProposalDetail(StoredMappingProposal proposal, ImportBatch batch,
            List<ValidationRun> validationRuns, List<DeliveryLogEntry> deliveryLog) {
    }

    public record ValidationSummary(List<Object> validRows, List<ValidationReport.RowError> rowErrors) {
        public static ValidationSummary from(ValidationReport report) {
            return new ValidationSummary(
                    report.validRows().stream().map(CanonicalValueJson::toJsonCompatible).toList(),
                    report.rowErrors());
        }
    }

    public record ValidationErrorResponse(List<String> problems) {
    }
}
