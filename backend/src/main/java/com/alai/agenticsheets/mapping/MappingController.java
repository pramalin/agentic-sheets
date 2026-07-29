package com.alai.agenticsheets.mapping;

import com.alai.agenticsheets.canonical.CanonicalModel;
import com.alai.agenticsheets.canonical.CanonicalModelRegistry;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Step 6's manual trigger for the mapping pipeline. Creates or reuses an
 * import_batch (deduped on filename + content hash), asks the agent to
 * propose a mapping, and persists it as a pending mapping_proposal --
 * nothing here validates the result against the ADT, writes anywhere
 * else, or calls a team's service. That only happens after a human
 * approves it (Step 7/8). Step 9's scheduled scanner will call this same
 * underlying flow automatically instead of a human hitting the endpoint.
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
        CanonicalModel model = registry.get(modelId);
        String contentHash = fileHasher.sha256(path);
        long batchId = importBatchRepository.findOrCreate(
                model.modelId(), clientId, path, contentHash, model.version());

        MappingProposal proposal = proposalService.propose(modelId, clientId, path, worksheet);
        long proposalId = mappingProposalRepository.save(batchId, model.version(), proposal);

        return new ProposeResponse(batchId, proposalId, proposal);
    }

    public record ProposeResponse(long importBatchId, long mappingProposalId, MappingProposal proposal) {
    }
}
