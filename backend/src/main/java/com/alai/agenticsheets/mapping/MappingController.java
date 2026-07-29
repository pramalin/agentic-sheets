package com.alai.agenticsheets.mapping;

import com.alai.agenticsheets.canonical.CanonicalModel;
import com.alai.agenticsheets.canonical.CanonicalModelRegistry;
import com.alai.agenticsheets.canonical.ClientConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * Step 6's manual trigger for the mapping pipeline, and Step 7's manual
 * trigger for approval -- creates or reuses an import_batch, asks the
 * agent to propose a mapping, persists it pending review
 * ({@code /propose}), and, once a human decides to approve it
 * ({@code /proposals/{id}/approve}), runs the deterministic validator
 * and dispatcher. There's no review UI yet (Step 8), so approval is a
 * plain endpoint call rather than a click -- Step 9's scheduled scanner
 * will call {@code /propose} automatically instead of a human hitting
 * it; approval, by design, always needs a human, automated or not.
 *
 * {@code /approve} and {@code /redeliver} are deliberately separate:
 * approving is a one-time human decision (claimed atomically, see
 * {@link MappingProposalRepository#claim}, and permanent once it
 * happens), while validate-and-dispatch can legitimately need retrying
 * (a transient delivery failure, or an unexpected exception mid-flight)
 * without re-litigating whether a human already approved this.
 *
 * Both endpoints share {@link #processDelivery}, which claims the
 * *batch* atomically before doing anything else -- a third-round
 * external review correctly caught that the proposal-level claim above
 * only protected the one-time approval decision, not the actual
 * delivery attempt: nothing stopped two concurrent {@code /redeliver}
 * calls, or a {@code /redeliver} racing {@code /approve}'s own in-flight
 * delivery, from both reaching {@link Dispatcher#dispatch} for the same
 * batch. See {@link ImportBatchRepository#claimForProcessing}.
 */
@RestController
@RequestMapping("/internal/mapping")
public class MappingController {

    private static final Logger log = LoggerFactory.getLogger(MappingController.class);

    /** Fresh off proposal approval -- the batch should still be
      * whatever {@code findOrCreate} left it as (PENDING; nothing sets
      * it otherwise before this point). */
    private static final Set<String> ELIGIBLE_FOR_APPROVE = Set.of("PENDING");

    /** Retrying after a recorded failure, or recovering a batch stuck at
      * APPROVED by a genuine process crash (one that killed the JVM
      * before even the catch block in {@link #processDelivery} could
      * run) -- deliberately excludes DELIVERED, since redelivering
      * something already successfully delivered is exactly the
      * duplicate-delivery risk this whole mechanism exists to prevent. */
    private static final Set<String> ELIGIBLE_FOR_REDELIVER =
            Set.of("APPROVED", "DELIVERY_FAILED", "PROCESSING_ERROR");

    private final MappingProposalService proposalService;
    private final ImportBatchRepository importBatchRepository;
    private final MappingProposalRepository mappingProposalRepository;
    private final FileHasher fileHasher;
    private final CanonicalModelRegistry registry;
    private final ProposalValidationService validationService;
    private final Dispatcher dispatcher;

    public MappingController(
            MappingProposalService proposalService,
            ImportBatchRepository importBatchRepository,
            MappingProposalRepository mappingProposalRepository,
            FileHasher fileHasher,
            CanonicalModelRegistry registry,
            ProposalValidationService validationService,
            Dispatcher dispatcher) {
        this.proposalService = proposalService;
        this.importBatchRepository = importBatchRepository;
        this.mappingProposalRepository = mappingProposalRepository;
        this.fileHasher = fileHasher;
        this.registry = registry;
        this.validationService = validationService;
        this.dispatcher = dispatcher;
    }

    @PostMapping("/propose")
    public ProposeResponse propose(
            @RequestParam String modelId,
            @RequestParam String clientId,
            @RequestParam String path,
            @RequestParam String worksheet) {
        // Resolved exactly once, threaded through the whole operation --
        // see MappingProposalService's javadoc for why re-fetching by ID
        // independently at each step was a real bug (config reload race).
        CanonicalModel model = registry.get(modelId);
        ClientConfig client = registry.getClient(clientId);

        String contentHash = fileHasher.sha256(path);
        long batchId = importBatchRepository.findOrCreate(
                model.modelId(), clientId, path, contentHash, worksheet, model.version());

        MappingProposal proposal = proposalService.propose(model, client, path, worksheet);
        long proposalId = mappingProposalRepository.save(batchId, model.version(), proposal);

        return new ProposeResponse(batchId, proposalId, proposal);
    }

    /**
     * Approves a pending proposal -- atomically claimed, see
     * {@link MappingProposalRepository#claim} -- then immediately
     * validates and dispatches.
     */
    @PostMapping("/proposals/{id}/approve")
    public ApproveResponse approve(
            @PathVariable long id,
            @RequestParam(defaultValue = "manual-api-call") String reviewedBy) {
        // Atomic compare-and-set, not check-then-act -- see the
        // repository method's javadoc for the race this closes. Not
        // reading the proposal first: an earlier version fetched it for
        // a "not PENDING (status: X)" error message, but X was always
        // the *pre-claim* status -- always "PENDING" in the exact
        // concurrent-race case that message exists to explain, since
        // this read necessarily happens before either request's claim
        // attempt. Misleading, not just imprecise. Claim first; only
        // look up the current status if it's actually needed to explain
        // a failure.
        if (!mappingProposalRepository.claim(id, reviewedBy)) {
            String currentStatus = mappingProposalRepository.findById(id).status();
            throw new IllegalStateException(
                    "proposal " + id + " could not be claimed -- current status is " + currentStatus
                            + ", not PENDING (already approved, possibly by a concurrent request racing this one, "
                            + "or never PENDING to begin with)");
        }

        StoredMappingProposal stored = mappingProposalRepository.findById(id);
        return processDelivery(id, stored, ELIGIBLE_FOR_APPROVE);
    }

    /**
     * Re-runs validation and dispatch for an already-approved proposal
     * -- for retrying after a transient delivery failure, or after an
     * unexpected exception left the batch in {@code PROCESSING_ERROR}.
     * Does not re-check the proposal's own approval status: once a
     * human has approved something, that decision doesn't need
     * repeating just because delivery needs another attempt. The batch
     * -level claim below is what actually guards against redelivering
     * something concurrently or something already {@code DELIVERED}.
     */
    @PostMapping("/proposals/{id}/redeliver")
    public ApproveResponse redeliver(@PathVariable long id) {
        StoredMappingProposal stored = mappingProposalRepository.findById(id);
        if (!"APPROVED".equals(stored.status())) {
            throw new IllegalStateException(
                    "proposal " + id + " is not APPROVED (status: " + stored.status()
                            + ") -- only an already-approved proposal can be redelivered");
        }
        return processDelivery(id, stored, ELIGIBLE_FOR_REDELIVER);
    }

    /**
     * Claims the batch atomically before doing anything else, then
     * checks for drift (canonical config version, source file content)
     * and either validates+dispatches or records exactly why it
     * couldn't. Shared by {@code /approve} and {@code /redeliver} --
     * the only difference between them is which batch statuses are
     * eligible to claim from.
     */
    private ApproveResponse processDelivery(long proposalId, StoredMappingProposal stored, Set<String> eligibleFromStatuses) {
        if (!importBatchRepository.claimForProcessing(stored.importBatchId(), eligibleFromStatuses)) {
            ImportBatch current = importBatchRepository.findById(stored.importBatchId());
            throw new IllegalStateException(
                    "batch " + stored.importBatchId() + " could not be claimed for processing -- current status is "
                            + current.status() + ", not one of " + eligibleFromStatuses
                            + " (already being processed concurrently, already delivered, or in a state that "
                            + "isn't safe to (re)deliver from)");
        }
        ImportBatch batch = importBatchRepository.findById(stored.importBatchId());

        CanonicalModel currentModel = registry.get(batch.modelId());
        if (currentModel.version() != stored.configVersion()) {
            // A distinct, precise status rather than leaving the batch
            // at whatever it was -- a second-round external review
            // correctly caught that a drift failure here used to leave
            // the batch looking like an ordinary, actionable APPROVED
            // in the database even though nothing had actually
            // succeeded, which would read as confusing or misleading in
            // a future review UI.
            importBatchRepository.updateStatus(batch.id(), "CONFIG_CHANGED");
            throw new IllegalStateException(
                    "canonical model '" + batch.modelId() + "' has moved from version " + stored.configVersion()
                            + " (when this proposal was created) to version " + currentModel.version()
                            + " -- refusing to validate against a config that may no longer match what was "
                            + "proposed. Re-run /propose against the current config before approving.");
        }

        // Hashed once already at /propose time and re-checked here --
        // still a real time-of-check/time-of-use window between this
        // check and the MCP read_rows calls ProposalValidationService is
        // about to make (a complete fix needs an immutable,
        // content-addressed copy of the source file, not attempted
        // here). What this narrows: re-hashing again below, after all
        // rows are read, catches a file replaced *during* that read
        // window too, not just before it -- cheap insurance, not a full
        // fix for the underlying window.
        String hashBeforeReading = fileHasher.sha256(batch.sourceFilename());
        if (!hashBeforeReading.equals(batch.contentHash())) {
            importBatchRepository.updateStatus(batch.id(), "SOURCE_CHANGED");
            throw new IllegalStateException(
                    "SOURCE_CHANGED: the file '" + batch.sourceFilename() + "' has different content now than "
                            + "when this batch was created (expected hash " + batch.contentHash() + ", observed "
                            + hashBeforeReading + ") -- refusing to approve a mapping against data that isn't what "
                            + "was actually reviewed. Re-run /propose against the current file.");
        }

        ClientConfig client = registry.getClient(batch.clientId());

        try {
            ValidationReport validationReport = validationService.validate(currentModel, client, batch, stored.proposal());

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
            // The proposal stays APPROVED -- that's a permanent record
            // of the human decision, not something to revert. The batch
            // moves to a distinct, clearly-recoverable state instead, so
            // /redeliver has something well-defined to retry rather than
            // this exception leaving things stuck with no path forward.
            log.error("validate-and-dispatch failed unexpectedly for proposal {} (batch {})", proposalId, batch.id(), e);
            importBatchRepository.updateStatus(batch.id(), "PROCESSING_ERROR");
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

    /**
     * Safety net for anything not already handled above. Added after a
     * live concurrency test surfaced a bare Spring Boot default error
     * page (no useful detail at all) for an exception thrown inside
     * {@link #processDelivery}. The full detail still needs the server
     * log (this only has the exception's own message, not a stack
     * trace) -- but a structured 500 with at least the message is still
     * strictly better than Spring's default HTML error page, which was
     * actively getting in the way of diagnosing what had actually gone
     * wrong from the client side.
     */
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

    /**
     * The API-facing view of a {@link ValidationReport} -- {@code
     * validRows} here is the same {@code CanonicalValueJson}-converted
     * wire format actually sent to the team's service (see
     * {@link Dispatcher}), not the raw internal {@code CanonicalValue}
     * tree. Returning the raw tree directly used to make an absent
     * optional field show up as {@code {}} in this response while the
     * team's service actually received {@code null} for it -- same
     * data, two different shapes, which reads like a bug when you're
     * comparing what this endpoint shows against what was actually
     * delivered. This response should always reflect reality, not an
     * implementation detail.
     */
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
