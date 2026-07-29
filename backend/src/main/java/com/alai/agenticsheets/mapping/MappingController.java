package com.alai.agenticsheets.mapping;

import com.alai.agenticsheets.canonical.CanonicalModel;
import com.alai.agenticsheets.canonical.CanonicalModelRegistry;
import com.alai.agenticsheets.canonical.ClientConfig;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
 */
@RestController
@RequestMapping("/internal/mapping")
public class MappingController {

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
     * Approves a pending proposal, then immediately validates it against
     * the ADT and dispatches whatever rows pass. Refuses to proceed if
     * the canonical model has moved to a different version since this
     * proposal was created -- see {@code mapping-notes.md}'s Step 7
     * notes for why re-validating against a config that may no longer
     * match what was actually proposed isn't safe to do silently.
     */
    @PostMapping("/proposals/{id}/approve")
    public ApproveResponse approve(
            @PathVariable long id,
            @RequestParam(defaultValue = "manual-api-call") String reviewedBy) {
        StoredMappingProposal stored = mappingProposalRepository.findById(id);
        if (!"PENDING".equals(stored.status())) {
            throw new IllegalStateException(
                    "proposal " + id + " is not PENDING (status: " + stored.status() + ")");
        }

        ImportBatch batch = importBatchRepository.findById(stored.importBatchId());

        CanonicalModel currentModel = registry.get(batch.modelId());
        if (currentModel.version() != stored.configVersion()) {
            throw new IllegalStateException(
                    "canonical model '" + batch.modelId() + "' has moved from version " + stored.configVersion()
                            + " (when this proposal was created) to version " + currentModel.version()
                            + " -- refusing to validate against a config that may no longer match what was "
                            + "proposed. Re-run /propose against the current config before approving.");
        }
        ClientConfig client = registry.getClient(batch.clientId());

        mappingProposalRepository.updateStatus(id, "APPROVED", reviewedBy);
        importBatchRepository.updateStatus(batch.id(), "APPROVED");

        ValidationReport validationReport = validationService.validate(currentModel, client, batch, stored.proposal());

        if (validationReport.validRows().isEmpty()) {
            importBatchRepository.updateStatus(batch.id(), "VALIDATION_FAILED");
            return new ApproveResponse(batch.id(), id, ValidationSummary.from(validationReport), null);
        }

        DispatchResult dispatchResult = dispatcher.dispatch(batch.id(), currentModel.target(), validationReport.validRows());
        String finalStatus = dispatchResult.outcome() == DispatchResult.Outcome.SUCCESS
                ? "DELIVERED" : "DELIVERY_FAILED";
        importBatchRepository.updateStatus(batch.id(), finalStatus);

        return new ApproveResponse(batch.id(), id, ValidationSummary.from(validationReport), dispatchResult);
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
