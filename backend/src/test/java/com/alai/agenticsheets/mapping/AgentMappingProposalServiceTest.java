package com.alai.agenticsheets.mapping;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The first test file for {@link AgentMappingProposalService}. Scoped
 * deliberately narrow: {@link #filterResolvedColumns} and
 * {@link #renderAlreadyResolvedNote} are pure logic (given a
 * {@link JsonMapper}, no model interaction at all) extracted to
 * package-private visibility specifically so they're directly testable
 * -- Local LLM phase, Step LLM-4's field-alias work (see
 * {@code docs/local-llm-enhancements.md}). The model-interaction path
 * itself (everything these two methods feed into --
 * {@code chatClient.prompt()...responseEntity(...)}) still has no test
 * coverage in this codebase; that remains real, separate work (a custom
 * test {@code ChatModel} bean, full Spring context wiring), not
 * something this file attempts or silently claims to cover.
 */
class AgentMappingProposalServiceTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private AgentMappingProposalService service() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(mock(ChatClient.class));
        return new AgentMappingProposalService(
                builder,
                mock(CanonicalModelPromptRenderer.class),
                mock(com.alai.agenticsheets.spreadsheet.SpreadsheetExplorerService.class),
                mock(SumTypeMappingResolver.class),
                mock(FieldAliasResolver.class),
                mock(MappingProposalStructuralValidator.class),
                jsonMapper,
                false);
    }

    private JsonNode tableWithColumns(String... headers) {
        StringBuilder columns = new StringBuilder();
        for (int i = 0; i < headers.length; i++) {
            if (i > 0) {
                columns.append(",");
            }
            columns.append("{\"header\": \"").append(headers[i]).append("\", \"sampleValues\": [\"x\", \"y\"]}");
        }
        String json = "{\"worksheet\": \"Holdings\", \"headerRowIndex\": 0, \"columns\": [" + columns + "]}";
        return jsonMapper.readTree(json);
    }

    private List<String> columnHeadersOf(JsonNode table) {
        return java.util.stream.StreamSupport.stream(table.get("columns").spliterator(), false)
                .map(col -> col.get("header").asText())
                .toList();
    }

    // --- filterResolvedColumns ---

    @Test
    void filterResolvedColumns_removesOnlyTheSpecifiedColumns() throws Exception {
        JsonNode table = tableWithColumns("Account", "Currency", "Custodian");

        JsonNode filtered = service().filterResolvedColumns(table, Set.of("Currency"));

        assertThat(columnHeadersOf(filtered)).containsExactly("Account", "Custodian");
    }

    @Test
    void filterResolvedColumns_removesMultipleColumns() throws Exception {
        JsonNode table = tableWithColumns("Account", "Currency", "Custodian", "CUSIP");

        JsonNode filtered = service().filterResolvedColumns(table, Set.of("Currency", "CUSIP"));

        assertThat(columnHeadersOf(filtered)).containsExactly("Account", "Custodian");
    }

    @Test
    void filterResolvedColumns_preservesOtherTopLevelKeysUnchanged() throws Exception {
        JsonNode table = tableWithColumns("Account", "Currency");

        JsonNode filtered = service().filterResolvedColumns(table, Set.of("Currency"));

        assertThat(filtered.get("worksheet").asText()).isEqualTo("Holdings");
        assertThat(filtered.get("headerRowIndex").asInt()).isEqualTo(0);
    }

    @Test
    void filterResolvedColumns_preservesOtherFieldsWithinRetainedColumns() throws Exception {
        JsonNode table = tableWithColumns("Account", "Currency");

        JsonNode filtered = service().filterResolvedColumns(table, Set.of("Currency"));

        JsonNode accountColumn = filtered.get("columns").get(0);
        assertThat(accountColumn.get("header").asText()).isEqualTo("Account");
        assertThat(accountColumn.get("sampleValues")).isNotNull();
    }

    @Test
    void filterResolvedColumns_removingNothingLeavesTableUnchanged() throws Exception {
        JsonNode table = tableWithColumns("Account", "Currency");

        JsonNode filtered = service().filterResolvedColumns(table, Set.of());

        assertThat(columnHeadersOf(filtered)).containsExactly("Account", "Currency");
    }

    @Test
    void filterResolvedColumns_removingEveryColumnLeavesAnEmptyColumnsArray() throws Exception {
        JsonNode table = tableWithColumns("Account", "Currency");

        JsonNode filtered = service().filterResolvedColumns(table, Set.of("Account", "Currency"));

        assertThat(filtered.get("columns").size()).isEqualTo(0);
    }

    // --- renderAlreadyResolvedNote ---

    @Test
    void renderAlreadyResolvedNote_emptyWhenNothingResolved() {
        FieldAliasResolver.Result empty = new FieldAliasResolver.Result(List.of(), Set.of());

        String note = service().renderAlreadyResolvedNote(empty);

        assertThat(note).isEmpty();
    }

    @Test
    void renderAlreadyResolvedNote_listsEachResolvedFieldAndItsSourceColumn() {
        MappingProposal.FieldMapping fm = new MappingProposal.FieldMapping(
                "currency", "Currency", null, null, null, null, 1.0, "deterministic");
        FieldAliasResolver.Result result = new FieldAliasResolver.Result(List.of(fm), Set.of("Currency"));

        String note = service().renderAlreadyResolvedNote(result);

        assertThat(note).contains("currency").contains("Currency").contains("Already resolved");
    }
}
