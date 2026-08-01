package com.alai.agenticsheets.mapping;

import com.alai.agenticsheets.canonical.CanonicalModel;
import com.alai.agenticsheets.canonical.ClientConfig;
import com.alai.agenticsheets.spreadsheet.SpreadsheetExplorerService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MappingResolutionServiceTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final ColumnFingerprint columnFingerprint = new ColumnFingerprint();
    private final ClientConfigFingerprint clientConfigFingerprint = new ClientConfigFingerprint();

    private final SpreadsheetExplorerService explorer = mock(SpreadsheetExplorerService.class);
    private final MappingMemoryRepository mappingMemoryRepository = mock(MappingMemoryRepository.class);
    private final AgentMappingProposalService agentService = mock(AgentMappingProposalService.class);

    private final MappingResolutionService resolutionService = new MappingResolutionService(
            explorer, columnFingerprint, clientConfigFingerprint, mappingMemoryRepository, agentService);

    // target/root/sourceFile are null -- nothing in this test's path
    // (MappingResolutionService.resolve, or the mocked agent service)
    // inspects them. synonyms is Map.of(), not null, respecting
    // CanonicalModel's own documented invariant ("never empty-vs-null
    // ambiguous") even in a test fixture.
    private final CanonicalModel model = new CanonicalModel("Holdings", 3, null, null, Map.of(), null);
    private final ClientConfig client = new ClientConfig("jpmc", "yyyy-MM-dd", Map.of());
    private final JsonNode table = jsonMapper.readTree("""
            {"columns": [{"header": "Account", "inferredType": "string"}]}
            """);

    @Test
    void aMemoryHitSkipsTheAgentEntirely() {
        when(explorer.describeTable("some/path.xlsx", "Holdings")).thenReturn(table);
        MappingProposal remembered = new MappingProposal(List.of(), List.of(), "remembered");
        MappingMemory memoryEntry = new MappingMemory(
                42L, "jpmc", "Holdings", "Holdings", 3, "cfg-hash", "col-hash", remembered, 7L, "ACTIVE", null);
        when(mappingMemoryRepository.findActiveMatch(anyString(), anyString(), anyString(), anyInt(),
                anyString(), anyString())).thenReturn(Optional.of(memoryEntry));

        ResolvedProposal result = resolutionService.resolve(model, client, "some/path.xlsx", "Holdings");

        assertThat(result.origin()).isEqualTo(ResolvedProposal.ORIGIN_MEMORY);
        assertThat(result.mappingMemoryId()).isEqualTo(42L);
        assertThat(result.proposal()).isEqualTo(remembered);
        verify(agentService, never()).propose(any(), any(), anyString(), anyString(), any());
        // Exactly once -- resolve() itself, not a second call inside
        // the agent path that a miss would have taken.
        verify(explorer, times(1)).describeTable(anyString(), anyString());
    }

    @Test
    void aMemoryMissFallsThroughToTheAgentWithTheAlreadyFetchedTable() {
        when(explorer.describeTable("some/path.xlsx", "Holdings")).thenReturn(table);
        when(mappingMemoryRepository.findActiveMatch(anyString(), anyString(), anyString(), anyInt(),
                anyString(), anyString())).thenReturn(Optional.empty());
        MappingProposal fresh = new MappingProposal(List.of(), List.of(), "fresh from the agent");
        when(agentService.propose(model, client, "some/path.xlsx", "Holdings", table)).thenReturn(fresh);

        ResolvedProposal result = resolutionService.resolve(model, client, "some/path.xlsx", "Holdings");

        assertThat(result.origin()).isEqualTo(ResolvedProposal.ORIGIN_AGENT);
        assertThat(result.mappingMemoryId()).isNull();
        assertThat(result.proposal()).isEqualTo(fresh);
        // The exact same table instance describeTable already returned
        // -- confirms no second describe_table call happened just to
        // hand the agent something to work with.
        verify(agentService).propose(model, client, "some/path.xlsx", "Holdings", table);
        verify(explorer, times(1)).describeTable(anyString(), anyString());
    }

    @Test
    void resultCarriesTheFingerprintsThatProducedItEitherWay() {
        when(explorer.describeTable(anyString(), anyString())).thenReturn(table);
        when(mappingMemoryRepository.findActiveMatch(anyString(), anyString(), anyString(), anyInt(),
                anyString(), anyString())).thenReturn(Optional.empty());
        when(agentService.propose(any(), any(), anyString(), anyString(), any()))
                .thenReturn(new MappingProposal(List.of(), List.of(), "s"));

        ResolvedProposal result = resolutionService.resolve(model, client, "some/path.xlsx", "Holdings");

        assertThat(result.columnFingerprint()).isEqualTo(columnFingerprint.hash(table));
        assertThat(result.clientConfigFingerprint()).isEqualTo(clientConfigFingerprint.hash(client));
    }
}
