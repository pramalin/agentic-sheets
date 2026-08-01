package com.alai.agenticsheets.mapping;

import com.alai.agenticsheets.canonical.CanonicalModel;
import com.alai.agenticsheets.canonical.CanonicalModelParser;
import com.alai.agenticsheets.canonical.ClientConfig;
import com.alai.agenticsheets.spreadsheet.SpreadsheetExplorerService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression test for a real bug caught while writing
 * {@link CanonicalRowBuilderTest}: the agent is explicitly told never to
 * propose a mapping for {@code client_id} (it's already known
 * externally), but {@code client_id} is still a required ADT field --
 * without {@link ProposalValidationService} injecting it, every row
 * would fail validation on a field the proposal was correctly told to
 * omit.
 */
class ProposalValidationServiceTest {

    private final CanonicalModelParser parser = new CanonicalModelParser();
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private CanonicalModel holdings() throws Exception {
        Path file = Path.of(getClass().getClassLoader()
                .getResource("canonical-models/holdings.yaml").toURI());
        return parser.parse(file);
    }

    @Test
    void validatesSuccessfullyEvenThoughTheProposalNeverMentionsClientId() throws Exception {
        SpreadsheetExplorerService explorer = mock(SpreadsheetExplorerService.class);
        String rowsJson = """
                {
                  "hasMore": false,
                  "rows": [
                    {"As Of Date": "2026-01-15", "Account": "ACC-1001", "CUSIP": "037833100",
                     "Class": "Equity", "Quantity": "5000", "Market Value": "926500.00"}
                  ]
                }
                """;
        when(explorer.readRows(anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(jsonMapper.readTree(rowsJson));

        ProposalValidationService service = new ProposalValidationService(
                explorer, new CanonicalRowBuilder(), jsonMapper);

        // Deliberately no client_id entry -- matching what the agent
        // actually produces per AgentMappingProposalService's system prompt.
        MappingProposal proposal = new MappingProposal(
                List.of(
                        new MappingProposal.FieldMapping("as_of_date", "As Of Date", null, null, null, null, 0.95, ""),
                        new MappingProposal.FieldMapping("account_id", "Account", null, null, null, null, 0.95, ""),
                        new MappingProposal.FieldMapping("security_id", "CUSIP", null, null, null, null, 0.95, ""),
                        new MappingProposal.FieldMapping("asset_class", "Class", null, "Equity", null, null, 0.9, ""),
                        new MappingProposal.FieldMapping("quantity", "Quantity", null, null, null, null, 0.95, ""),
                        new MappingProposal.FieldMapping("market_value", "Market Value", null, null, null, null, 0.95, ""),
                        new MappingProposal.FieldMapping("currency", null, null, "USD", null, null, 0.95, "")),
                List.of(),
                "test");

        ClientConfig jpmc = new ClientConfig("jpmc", "yyyy-MM-dd", java.util.Map.of());
        ImportBatch batch = new ImportBatch(1L, "Holdings", "jpmc", "holdings_jpmc.xlsx", "hash", "Holdings", 1, "APPROVED");

        ValidationReport report = service.validate(holdings(), jpmc, batch, proposal);

        assertThat(report.hasErrors()).as("row errors: %s", report.rowErrors()).isFalse();
        assertThat(report.validRows()).hasSize(1);
    }
}
