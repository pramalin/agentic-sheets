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

    /** Batch states safe to start a *new* proposal from. See
      * mapping-notes.md's Step 7.4 section for the full reasoning. */
    private static final Set<String> ELIGIBLE_FOR_PROPOSE =
            Set.of("PENDING", "REJECTED", "VALIDATION_FAILED", "SOURCE_CHANGED", "CONFIG_CHANGED", "PROPOSING_ERROR");

    /** Retrying after a recorded failure, or recovering a batch stuck at
      * APPROVED by a genuine process crash -- deliberately excludes
      * DELIVERED and PROCESSING/PROPOSING. Deliberately disjoint from
      * {@link #ELIGIBLE_FOR_PROPOSE} -- no status is eligible for both a
      * fresh proposal and a redelivery, which is what lets
      * {@code /redeliver} use a single, untransacted claim safely: no
      * concurrent {@code /propose} could ever compete for a batch in
      * one of these states. */
    private static final Set<String> ELIGIBLE_FOR_REDELIVER =
            Set.of("APPROVED", "DELIVERY_FAILED", "PROCESSING_ERROR");

    private final MappingProposalService proposalService;
    private final ImportBatchRepository importBatchRepository;
    private final MappingProposalRepository mappingProposalRepository;
    private final ValidationRunRepository validationRunRepository;
    private final DeliveryLogRepository deliveryLogRepository;
    private final FileHasher fileHasher;
    private final CanonicalModelRegistry registry;
    private final ProposalValidationService validationService;
    private final Dispatcher dispatcher;
    private final ProposalDecisionService proposalDecisionService;

    public MappingController(
            MappingProposalService proposalService,
            ImportBatchRepository importBatchRepository,
            MappingProposalRepository mappingProposalRepository,
            ValidationRunRepository validationRunRepository,
            DeliveryLogRepository deliveryLogRepository,
            FileHasher fileHasher,
            CanonicalModelRegistry registry,
            ProposalValidationService validationService,
            Dispatcher dispatcher,
            ProposalDecisionService proposalDecisionService) {
        this.proposalService = proposalService;
        this.importBatchRepository = importBatchRepository;
        this.mappingProposalRepository = mappingProposalRepository;
        this.validationRunRepository = validationRunRepository;
        this.deliveryLogRepository = deliveryLogRepository;
        this.fileHasher = fileHasher;
        this.registry = registry;
        this.validationService = validationService;
        this.dispatcher = dispatcher;
        this.proposalDecisionService = proposalDecisionService;
    }

    /**
     * Creates or reuses an import_batch, then either returns the
     * existing PENDING proposal for it (fast path, no model call) or
     * claims the batch into {@code PROPOSING} *before* calling the
     * model. The model call itself stays outside any transaction (a
     * slow network call, not a database operation) -- but persisting
     * the result and releasing the batch back to {@code PENDING} are
     * one transaction, via {@link ProposalDecisionService#saveProposalAndReleaseBatch}.
     */
    @PostMapping("/propose")
    public ProposeResponse propose(
            @RequestParam String modelId,
            @RequestParam String clientId,
            @RequestParam String path,
            @RequestParam String worksheet) {
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

        if (!importBatchRepository.claimForProcessing(batchId, ELIGIBLE_FOR_PROPOSE, "PROPOSING")) {
            ImportBatch current = importBatchRepository.findById(batchId);
            throw new IllegalStateException(
                    "batch " + batchId + " could not be claimed for proposing -- current status is "
                            + current.status() + ", not one of " + ELIGIBLE_FOR_PROPOSE
                            + " (already being processed concurrently, already delivered, or a state that needs "
                            + "/redeliver instead of a fresh proposal)");
        }

        MappingProposal proposal;
        try {
            proposal = proposalService.propose(model, client, path, worksheet);
        } catch (RuntimeException e) {
            log.error("propose (model call) failed unexpectedly for batch {}", batchId, e);
            importBatchRepository.updateStatusIfCurrent(batchId, "PROPOSING", "PROPOSING_ERROR");
            throw e;
        }

        long proposalId;
        try {
            proposalId = proposalDecisionService.saveProposalAndReleaseBatch(batchId, model.version(), proposal);
        } catch (RuntimeException e) {
            log.error("propose (persist+release) failed unexpectedly for batch {}", batchId, e);
            importBatchRepository.updateStatusIfCurrent(batchId, "PROPOSING", "PROPOSING_ERROR");
            throw e;
        }

        return new ProposeResponse(batchId, proposalId, proposal);
    }

    /**
     * The Step 8 review queue -- most recent first, joined with batch
     * context (client, file, worksheet) so it's actually usable, not
     * just a list of proposal IDs. {@code status} narrows to one status
     * (typically {@code PENDING}, "what needs review right now"); omit
     * it for a broader history view.
     */
    @GetMapping("/proposals")
    public List<ProposalQueueEntry> list(
            @RequestParam(required = false) String status,
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
    }

    /**
     * Re-runs validation and dispatch for an already-approved proposal.
     * A single, untransacted claim is genuinely sufficient here (unlike
     * {@code /approve} and {@code /reject}): {@code /redeliver} never
     * changes the proposal's own status (it's already permanently
     * APPROVED), so there's only one row's worth of state to claim, and
     * {@link #ELIGIBLE_FOR_REDELIVER} is deliberately disjoint from
     * {@link #ELIGIBLE_FOR_PROPOSE} -- no concurrent {@code /propose}
     * could ever be racing for a batch in one of these states.
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

    public record ProposeResponse(long importBatchId, long mappingProposalId, MappingProposal proposal) {
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
