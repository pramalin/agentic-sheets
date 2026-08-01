package com.alai.agenticsheets.mapping;

import com.alai.agenticsheets.canonical.CanonicalModel;
import com.alai.agenticsheets.canonical.ClientConfig;
import com.alai.agenticsheets.spreadsheet.SpreadsheetExplorerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.Optional;

/**
 * Step 10: the one entry point both the manual {@code /propose} path
 * and Step 9's scanner actually call to get a proposal -- deliberately
 * not folded into {@link AgentMappingProposalService}, which now
 * represents specifically "make a real model call," not "decide
 * whether one is needed at all." See {@code mapping-notes.md}'s Step 10
 * section for the full design history (three external review rounds)
 * behind why this split exists and what it's conservative about.
 *
 * One {@code describe_table} call, used for both fingerprinting and (on
 * a miss) the agent prompt -- not two. Memory lookup happens first,
 * scoped to {@code (clientId, worksheet, modelId, modelVersion,
 * clientConfigFingerprint, columnFingerprint)} -- an exact match on
 * every one of those means a human already approved a mapping for this
 * exact structural situation, with zero row-validation errors, and
 * nothing about the model, client conventions, or column shape has
 * changed since. Anything else -- a genuinely new structure, a changed
 * model version, a changed client date format -- falls through to a
 * real agent call, same as before Step 10 existed.
 */
@Service
public class MappingResolutionService {

    private static final Logger log = LoggerFactory.getLogger(MappingResolutionService.class);

    private final SpreadsheetExplorerService explorer;
    private final ColumnFingerprint columnFingerprint;
    private final ClientConfigFingerprint clientConfigFingerprint;
    private final MappingMemoryRepository mappingMemoryRepository;
    private final AgentMappingProposalService agentService;

    public MappingResolutionService(
            SpreadsheetExplorerService explorer,
            ColumnFingerprint columnFingerprint,
            ClientConfigFingerprint clientConfigFingerprint,
            MappingMemoryRepository mappingMemoryRepository,
            AgentMappingProposalService agentService) {
        this.explorer = explorer;
        this.columnFingerprint = columnFingerprint;
        this.clientConfigFingerprint = clientConfigFingerprint;
        this.mappingMemoryRepository = mappingMemoryRepository;
        this.agentService = agentService;
    }

    public ResolvedProposal resolve(CanonicalModel model, ClientConfig client, String sourcePath, String worksheet) {
        JsonNode table = explorer.describeTable(sourcePath, worksheet);
        String colFingerprint = columnFingerprint.hash(table);
        String cfgFingerprint = clientConfigFingerprint.hash(client);

        Optional<MappingMemory> remembered = mappingMemoryRepository.findActiveMatch(
                client.clientId(), worksheet, model.modelId(), model.version(), cfgFingerprint, colFingerprint);

        if (remembered.isPresent()) {
            log.info("Reusing mapping memory {} for {}/{}/{} -- skipping the model call",
                    remembered.get().id(), client.clientId(), model.modelId(), worksheet);
            return ResolvedProposal.fromMemory(
                    remembered.get().proposal(), remembered.get().id(), colFingerprint, cfgFingerprint);
        }

        MappingProposal proposal = agentService.propose(model, client, sourcePath, worksheet, table);
        return ResolvedProposal.fromAgent(proposal, colFingerprint, cfgFingerprint);
    }
}
