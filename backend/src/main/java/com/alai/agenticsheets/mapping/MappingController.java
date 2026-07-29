package com.alai.agenticsheets.mapping;

import com.alai.agenticsheets.canonical.CanonicalModel;
import com.alai.agenticsheets.canonical.CanonicalModelRegistry;
import com.alai.agenticsheets.canonical.ClientConfig;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Step 6's manual trigger for the mapping pipeline. Creates or reuses an
 * import_batch (deduped on filename + content hash + worksheet + model +
 * client + config version), asks the agent to propose a mapping, and
 * persists it as a pending mapping_proposal -- nothing here validates the
 * result against actual canonical *rows*, writes anywhere else, or calls
 * a team's service. That only happens after a human approves it (Step
 * 7/8). Step 9's scheduled scanner will call this same underlying flow
 * automatically instead of a human hitting the endpoint.
 */
@RestController
@RequestMapping("/internal/mapping")
public class MappingController {

    private final MappingProposalService proposalService;
    private final ImportBatchRepository importBatchRepository;
    private final MappingProposalRepository mappingProposalRepository;
    private final FileHasher fileHasher;
    private final CanonicalModelRegistry registry;

    public MappingController(
            MappingProposalService proposalService,
            ImportBatchRepository importBatchRepository,
            MappingProposalRepository mappingProposalRepository,
            FileHasher fileHasher,
            CanonicalModelRegistry registry) {
        this.proposalService = proposalService;
        this.importBatchRepository = importBatchRepository;
        this.mappingProposalRepository = mappingProposalRepository;
        this.fileHasher = fileHasher;
        this.registry = registry;
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

    @ExceptionHandler(MappingProposalValidationException.class)
    public ResponseEntity<ValidationErrorResponse> handleValidationFailure(MappingProposalValidationException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ValidationErrorResponse(e.problems()));
    }

    public record ProposeResponse(long importBatchId, long mappingProposalId, MappingProposal proposal) {
    }

    public record ValidationErrorResponse(List<String> problems) {
    }
}
