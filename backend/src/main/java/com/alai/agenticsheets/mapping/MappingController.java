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
 * and dispatcher. Step 8 adds the read endpoints a real UI needs (the
 * queue, a proposal's full detail including validation/delivery
 * history) and the other terminal decision a human can make
 * ({@code /proposals/{id}/reject}).
 *
 * Three exclusive batch operations -- proposing, approving+delivering,
 * redelivering -- all share the same atomic-claim idiom
 * ({@link ImportBatchRepository#claimForProcessing}): claim the batch
 * into a transitional status (PROPOSING or PROCESSING) *before* the
 * slow part (an LLM call, or an HTTP dispatch) starts, not after. A
 * fifth-round external review correctly caught that {@code /propose}
 * was the one operation *not* following this pattern -- it created a
 * new proposal without ever claiming the batch first, which meant (a)
 * a slow LLM call left the batch unprotected the whole time it ran, and
 * (b) a batch left in a non-PENDING status by a prior rejection or
 * failure never got reset, so the *new* proposal it produced could
 * never actually be approved -- {@code /approve}'s own batch claim only
 * accepts PENDING, and the proposal was already permanently APPROVED by
 * the time that claim failed, with no path forward at all.
 */
@RestController
@RequestMapping("/internal/mapping")
public class MappingController {

    private static final Logger log = LoggerFactory.getLogger(MappingController.class);

    /** Batch states safe to start a *new* proposal from. PENDING is a
      * freshly-created batch with no proposal yet; REJECTED/
      * VALIDATION_FAILED/SOURCE_CHANGED/CONFIG_CHANGED/PROPOSING_ERROR
      * are all "something didn't go through, try again" states this
      * project's own error messages already told callers to re-run
      * /propose from. Deliberately excludes DELIVERY_FAILED and
      * PROCESSING_ERROR -- per the review, those already have a correct
      * recovery path (/redeliver, retrying the *existing* approved
      * proposal), and a reviewer wanting a genuinely different mapping
      * for either should get there through an explicit revision
      * mechanism (not yet built) rather than a fresh /propose call that
      * silently orphans the already-approved one. */
    private static final Set<String> ELIGIBLE_FOR_PROPOSE =
            Set.of("PENDING", "REJECTED", "VALIDATION_FAILED", "SOURCE_CHANGED", "CONFIG_CHANGED", "PROPOSING_ERROR");

    /** Fresh off proposal approval -- the batch should be PENDING,
      * maintained as an invariant by {@link #propose} itself now (every
      * successful proposal leaves the batch PENDING; nothing else
      * touches a PENDING-proposal/PENDING-batch pair in between). */
    private static final Set<String> ELIGIBLE_FOR_APPROVE = Set.of("PENDING");

    /** Retrying after a recorded failure, or recovering a batch stuck at
      * APPROVED by a genuine process crash -- deliberately excludes
      * DELIVERED (redelivering something already successfully delivered
      * is exactly the duplicate-delivery risk this whole mechanism
      * exists to prevent) and PROCESSING/PROPOSING (a batch legitimately
      * being worked on right now by another request). */
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

    public MappingController(
            MappingProposalService proposalService,
            ImportBatchRepository importBatchRepository,
            MappingProposalRepository mappingProposalRepository,
            ValidationRunRepository validationRunRepository,
            DeliveryLogRepository deliveryLogRepository,
            FileHasher fileHasher,
            CanonicalModelRegistry registry,
            ProposalValidationService validationService,
            Dispatcher dispatcher) {
        this.proposalService = proposalService;
        this.importBatchRepository = importBatchRepository;
        this.mappingProposalRepository = mappingProposalRepository;
        this.validationRunRepository = validationRunRepository;
        this.deliveryLogRepository = deliveryLogRepository;
        this.fileHasher = fileHasher;
        this.registry = registry;
        this.validationService = validationService;
        this.dispatcher = dispatcher;
    }

    /**
     * Creates or reuses an import_batch, then either returns the
     * existing PENDING proposal for it (fast path, no model call) or
     * claims the batch into {@code PROPOSING} *before* calling the
     * model and asks for a new one. Claiming first, not after, is what
     * closes the race a fifth-round external review found: without it,
     * a slow LLM call left the batch fully exposed to a concurrent
     * {@code /approve} or {@code /redeliver} the entire time it ran.
     * On success, the batch always ends up back at {@code PENDING} --
     * that invariant (PENDING proposal implies PENDING batch) is what
     * lets {@code /approve}'s own claim stay simple.
     */
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

        try {
            MappingProposal proposal = proposalService.propose(model, client, path, worksheet);
            long proposalId = mappingProposalRepository.save(batchId, model.version(), proposal);
            importBatchRepository.updateStatus(batchId, "PENDING");
            return new ProposeResponse(batchId, proposalId, proposal);
        } catch (RuntimeException e) {
            log.error("propose failed unexpectedly for batch {}", batchId, e);
            importBatchRepository.updateStatusIfCurrent(batchId, "PROPOSING", "PROPOSING_ERROR");
            throw e;
        }
    }

    /**
     * The Step 8 review queue -- most recent first. {@code status}
     * narrows to one status (typically {@code PENDING}, "what needs
     * review right now"); omit it for a broader history view.
     */
    @GetMapping("/proposals")
    public List<StoredMappingProposal> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit) {
        return mappingProposalRepository.findAll(status, limit);
    }

    /**
     * Everything a review screen needs about one proposal: the proposal
     * itself, its batch, every validation attempt, and every delivery
     * attempt this specific proposal produced -- not the batch's whole
     * delivery history, which could include attempts from a different,
     * earlier proposal against the same batch (reject, re-propose,
     * approve a different one). An external review correctly caught
     * that this used to look up delivery attempts by batch ID, so
     * proposal B's detail view could show proposal A's delivery
     * attempts.
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
     * Approves a pending proposal -- atomically claimed, see
     * {@link MappingProposalRepository#claim} -- then immediately
     * validates and dispatches.
     *
     * The proposal claim and the batch claim inside {@link
     * #processDelivery} are two separate statements, not one database
     * transaction -- a fifth-round external review suggested wrapping
     * them together so either both succeed or both roll back. Not done:
     * {@link #propose} now maintains "a PENDING proposal implies a
     * PENDING batch" as an invariant (every successful proposal leaves
     * the batch at PENDING, and nothing else touches that pairing
     * in between, since proposing, approving, and redelivering all now
     * claim the batch exclusively before acting on it). Given that
     * invariant holds, the batch claim right after a successful proposal
     * claim should never actually fail under normal operation -- there's
     * no longer a window where the two can disagree. This is reasoned
     * confidence, not a proof; if a future case surfaces where they
     * still can disagree, wrapping both in one transaction is the
     * strictly safer fallback and should be revisited then.
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
     * The other terminal decision a human can make about a pending
     * proposal -- same atomic compare-and-set idiom as {@code /approve},
     * see {@link MappingProposalRepository#reject}.
     */
    @PostMapping("/proposals/{id}/reject")
    public void reject(
            @PathVariable long id,
            @RequestParam(defaultValue = "manual-api-call") String reviewedBy,
            @RequestParam(required = false) String reason) {
        if (!mappingProposalRepository.reject(id, reviewedBy, reason)) {
            String currentStatus = mappingProposalRepository.findById(id).status();
            throw new IllegalStateException(
                    "proposal " + id + " could not be rejected -- current status is " + currentStatus
                            + ", not PENDING");
        }
        StoredMappingProposal stored = mappingProposalRepository.findById(id);
        importBatchRepository.updateStatus(stored.importBatchId(), "REJECTED");
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
     * BREAK-GLASS OPERATION -- not an ordinary review-screen action, and
     * Step 8b's UI should not expose this as a routine button. Manually
     * recovers a batch stuck in {@code PROCESSING} back to {@code
     * PROCESSING_ERROR}, making it eligible for {@code /redeliver}.
     *
     * This cannot tell the difference between "the previous process
     * genuinely died" and "the previous request is just slow" -- an
     * external review correctly flagged that calling this against a
     * merely-slow (not actually dead) request recreates exactly the
     * concurrent-delivery race the atomic claim exists to prevent: the
     * "recovered" batch becomes claimable again while the original
     * request is still running toward it. Only use this after
     * separately confirming the process that claimed the batch is
     * actually gone (a crashed container, a killed process) -- never as
     * a first response to "this seems to be taking a while." The shared
     * API key that authenticates this call is not a distinct
     * administrative role; anyone who can call any {@code /internal/**}
     * endpoint can call this one too. A real processing lease
     * (timestamp + token, reclaimed only after a calibrated staleness
     * window) remains the long-term fix -- deferred, see
     * {@code mapping-notes.md}.
     */
    @PostMapping("/batches/{id}/recover-stuck-processing")
    public void recoverStuckProcessing(@PathVariable long id) {
        if (!importBatchRepository.updateStatusIfCurrent(id, "PROCESSING", "PROCESSING_ERROR")) {
            ImportBatch current = importBatchRepository.findById(id);
            throw new IllegalStateException(
                    "batch " + id + " is not currently PROCESSING (status: " + current.status()
                            + ") -- nothing to recover");
        }
    }

    /**
     * Claims the batch atomically before doing anything else, then
     * checks for drift (canonical config version, source file content)
     * and either validates+dispatches or records exactly why it
     * couldn't. Shared by {@code /approve} and {@code /redeliver} --
     * the only difference between them is which batch statuses are
     * eligible to claim from. Every validation attempt is persisted via
     * {@link ValidationRunRepository}, regardless of outcome -- Step 8's
     * review screen needs durable history, not just this response.
     *
     * Everything from immediately after the claim onward runs inside
     * one try/catch -- see the class javadoc for why an earlier version
     * left some of this outside the try block, and why that was a real
     * bug, not a style preference.
     */
    private ApproveResponse processDelivery(long proposalId, StoredMappingProposal stored, Set<String> eligibleFromStatuses) {
        if (!importBatchRepository.claimForProcessing(stored.importBatchId(), eligibleFromStatuses, "PROCESSING")) {
            ImportBatch current = importBatchRepository.findById(stored.importBatchId());
            throw new IllegalStateException(
                    "batch " + stored.importBatchId() + " could not be claimed for processing -- current status is "
                            + current.status() + ", not one of " + eligibleFromStatuses
                            + " (already being processed concurrently, already delivered, or in a state that "
                            + "isn't safe to (re)deliver from)");
        }

        long batchId = stored.importBatchId();
        try {
            ImportBatch batch = importBatchRepository.findById(batchId);

            CanonicalModel currentModel = registry.get(batch.modelId());
            if (currentModel.version() != stored.configVersion()) {
                // A distinct, precise status rather than leaving the
                // batch at whatever it was -- a second-round external
                // review correctly caught that a drift failure here used
                // to leave the batch looking like an ordinary, actionable
                // APPROVED in the database even though nothing had
                // actually succeeded. Setting this status *inside* the
                // try block, before throwing, is exactly why the catch
                // block below has to be conditional (updateStatusIfCurrent,
                // not a plain updateStatus) -- a fourth-round review
                // correctly caught that an unconditional catch-all
                // clobbered this more specific status right back to
                // PROCESSING_ERROR.
                importBatchRepository.updateStatus(batch.id(), "CONFIG_CHANGED");
                throw new IllegalStateException(
                        "canonical model '" + batch.modelId() + "' has moved from version " + stored.configVersion()
                                + " (when this proposal was created) to version " + currentModel.version()
                                + " -- refusing to validate against a config that may no longer match what was "
                                + "proposed. Re-run /propose against the current config before approving.");
            }

            // Hashed once already at /propose time and re-checked here --
            // still a real time-of-check/time-of-use window between this
            // check and the MCP read_rows calls ProposalValidationService
            // is about to make (a complete fix needs an immutable,
            // content-addressed copy of the source file, not attempted
            // here). What this narrows: re-hashing again below, after all
            // rows are read, catches a file replaced *during* that read
            // window too, not just before it -- cheap insurance, not a
            // full fix for the underlying window.
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
            // The proposal stays APPROVED -- that's a permanent record
            // of the human decision, not something to revert.
            //
            // updateStatusIfCurrent, not updateStatus: only overwrites if
            // the batch is still exactly PROCESSING. If a more specific
            // status (CONFIG_CHANGED, SOURCE_CHANGED) was already
            // recorded a moment ago, right before this exception, that
            // status is preserved -- a fourth-round external review
            // correctly caught that a plain unconditional updateStatus
            // here was clobbering exactly that.
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

    public record ProposalDetail(StoredMappingProposal proposal, ImportBatch batch,
            List<ValidationRun> validationRuns, List<DeliveryLogEntry> deliveryLog) {
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
