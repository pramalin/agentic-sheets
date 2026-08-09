package com.alai.agenticsheets.mapping;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

    // =====================================================================
    // propose() -- the skip-the-model-call optimization, and the exact
    // severe bug an external review found in the merge that made it
    // possible. See docs/local-llm-enhancements.md.
    // =====================================================================

    /** Holds the individual mocks a propose()-level test needs to stub
      * and verify, since {@link #service()} builds them internally and
      * doesn't expose them. */
    private record Harness(
            AgentMappingProposalService service,
            ChatClient chatClient,
            FieldAliasResolver fieldAliasResolver,
            SumTypeMappingResolver sumTypeResolver,
            MappingProposalStructuralValidator structuralValidator) {
    }

    private Harness harness() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class);
        when(builder.build()).thenReturn(chatClient);
        FieldAliasResolver fieldAliasResolver = mock(FieldAliasResolver.class);
        SumTypeMappingResolver sumTypeResolver = mock(SumTypeMappingResolver.class);
        MappingProposalStructuralValidator structuralValidator = mock(MappingProposalStructuralValidator.class);
        AgentMappingProposalService service = new AgentMappingProposalService(
                builder,
                mock(CanonicalModelPromptRenderer.class),
                mock(com.alai.agenticsheets.spreadsheet.SpreadsheetExplorerService.class),
                sumTypeResolver,
                fieldAliasResolver,
                structuralValidator,
                jsonMapper,
                false);
        return new Harness(service, chatClient, fieldAliasResolver, sumTypeResolver, structuralValidator);
    }

    private com.alai.agenticsheets.canonical.CanonicalModel dummyModel() {
        return mock(com.alai.agenticsheets.canonical.CanonicalModel.class);
    }

    private com.alai.agenticsheets.canonical.ClientConfig dummyClient() {
        com.alai.agenticsheets.canonical.ClientConfig client =
                mock(com.alai.agenticsheets.canonical.ClientConfig.class);
        when(client.clientId()).thenReturn("test-client");
        return client;
    }

    @Test
    void everyColumnDeterministicallyResolved_skipsTheModelCallEntirely() {
        Harness h = harness();
        JsonNode table = tableWithColumns("Currency", "Custodian");
        Set<String> observed = Set.of("Currency", "Custodian");

        List<MappingProposal.FieldMapping> deterministic = List.of(
                new MappingProposal.FieldMapping("currency", "Currency", null, null, null, null, 1.0, "det"),
                new MappingProposal.FieldMapping("custodian", "Custodian", null, null, null, null, 1.0, "det"));
        when(h.fieldAliasResolver().resolve(any(), any(), org.mockito.ArgumentMatchers.eq(observed)))
                .thenReturn(new FieldAliasResolver.Result(deterministic, observed));

        // Pass the merged proposal straight through unchanged -- this
        // test is about whether the model gets called, not about
        // SumTypeMappingResolver's own behavior.
        when(h.sumTypeResolver().resolve(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> new SumTypeMappingResolver.Result(inv.getArgument(0), List.of()));
        when(h.structuralValidator().validate(any(), any(), any())).thenReturn(List.of());
        when(h.structuralValidator().validateColumnCoverage(any(), any())).thenReturn(List.of());

        MappingProposal result = h.service().propose(dummyModel(), dummyClient(), "f.xlsx", "Holdings", table);

        assertThat(result.fieldMappings()).hasSize(2);
        assertThat(result.fieldMappings())
                .extracting(MappingProposal.FieldMapping::canonicalFieldPath)
                .containsExactlyInAnyOrder("currency", "custodian");
        // The actual proof: the model was never touched.
        org.mockito.Mockito.verifyNoInteractions(h.chatClient());
    }

    @Test
    void notEveryColumnResolved_doesNotSkipTheModelCall() {
        Harness h = harness();
        JsonNode table = tableWithColumns("Currency", "Valuation Px");
        Set<String> observed = Set.of("Currency", "Valuation Px");

        // Only "Currency" resolves -- "Valuation Px" is genuinely left
        // for the model, so the skip condition must not fire.
        List<MappingProposal.FieldMapping> deterministic = List.of(
                new MappingProposal.FieldMapping("currency", "Currency", null, null, null, null, 1.0, "det"));
        when(h.fieldAliasResolver().resolve(any(), any(), org.mockito.ArgumentMatchers.eq(observed)))
                .thenReturn(new FieldAliasResolver.Result(deterministic, Set.of("Currency")));

        org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec requestSpec =
                mock(org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec.class);
        when(h.chatClient().prompt()).thenReturn(requestSpec);
        when(requestSpec.system(org.mockito.ArgumentMatchers.anyString())).thenReturn(requestSpec);
        when(requestSpec.user(org.mockito.ArgumentMatchers.anyString())).thenReturn(requestSpec);
        org.springframework.ai.chat.client.ChatClient.CallResponseSpec callSpec =
                mock(org.springframework.ai.chat.client.ChatClient.CallResponseSpec.class);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.responseEntity(MappingProposal.class))
                .thenReturn(new org.springframework.ai.chat.client.ResponseEntity<>(null, null));

        when(h.sumTypeResolver().resolve(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> new SumTypeMappingResolver.Result(inv.getArgument(0), List.of()));
        when(h.structuralValidator().validate(any(), any(), any())).thenReturn(List.of());
        when(h.structuralValidator().validateColumnCoverage(any(), any())).thenReturn(List.of("unresolved"));

        org.junit.jupiter.api.Assertions.assertThrows(MappingProposalValidationException.class,
                () -> h.service().propose(dummyModel(), dummyClient(), "f.xlsx", "Holdings", table));

        // The actual proof: the model WAS called this time.
        org.mockito.Mockito.verify(h.chatClient()).prompt();
    }

    @Test
    void modelStaleMentionOfADeterministicallyResolvedColumn_filteredNotContradictory() {
        // A real bug found against actual Qwen 2.5 3B output: the
        // "already resolved" note names a deterministically-resolved
        // column explicitly (by design -- see renderAlreadyResolvedNote),
        // and the model sometimes echoes that name back into its OWN
        // unmappedSourceColumns anyway, even though that column was
        // never actually in the table it was shown. Before this fix,
        // that stale mention collided with the deterministic
        // FieldMapping for the same column, and
        // validateColumnCoverage correctly-but-misleadingly flagged it
        // as "mapped AND unmapped -- contradictory." The fix: filter
        // the model's own unmappedSourceColumns against what was
        // already deterministically resolved, symmetric with how a
        // duplicate FieldMapping is already dropped. This test proves
        // the merged proposal excludes the stale mention entirely,
        // rather than surfacing it as a contradiction.
        Harness h = harness();
        JsonNode table = tableWithColumns("Currency", "Valuation Px");
        Set<String> observed = Set.of("Currency", "Valuation Px");

        List<MappingProposal.FieldMapping> deterministic = List.of(
                new MappingProposal.FieldMapping("currency", "Currency", null, null, null, null, 1.0, "det"));
        when(h.fieldAliasResolver().resolve(any(), any(), org.mockito.ArgumentMatchers.eq(observed)))
                .thenReturn(new FieldAliasResolver.Result(deterministic, Set.of("Currency")));

        // The model's response: correctly maps the genuinely unresolved
        // column, but ALSO stale-mentions "Currency" (already
        // deterministically resolved, never shown to it) in its own
        // unmappedSourceColumns.
        MappingProposal modelResponse = new MappingProposal(
                List.of(new MappingProposal.FieldMapping(
                        "market_price", "Valuation Px", null, null, null, null, 0.9, "model")),
                List.of("Currency"),
                "model summary");

        org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec requestSpec =
                mock(org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec.class);
        when(h.chatClient().prompt()).thenReturn(requestSpec);
        when(requestSpec.system(org.mockito.ArgumentMatchers.anyString())).thenReturn(requestSpec);
        when(requestSpec.user(org.mockito.ArgumentMatchers.anyString())).thenReturn(requestSpec);
        org.springframework.ai.chat.client.ChatClient.CallResponseSpec callSpec =
                mock(org.springframework.ai.chat.client.ChatClient.CallResponseSpec.class);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.responseEntity(MappingProposal.class))
                .thenReturn(new org.springframework.ai.chat.client.ResponseEntity<>(null, modelResponse));

        when(h.sumTypeResolver().resolve(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> new SumTypeMappingResolver.Result(inv.getArgument(0), List.of()));
        when(h.structuralValidator().validate(any(), any(), any())).thenReturn(List.of());
        when(h.structuralValidator().validateColumnCoverage(any(), any())).thenReturn(List.of());

        MappingProposal result = h.service().propose(dummyModel(), dummyClient(), "f.xlsx", "Holdings", table);

        assertThat(result.fieldMappings()).hasSize(2);
        assertThat(result.fieldMappings())
                .extracting(MappingProposal.FieldMapping::canonicalFieldPath)
                .containsExactlyInAnyOrder("currency", "market_price");
        // The actual proof: "Currency" does NOT appear in the final
        // unmappedSourceColumns -- the stale mention was filtered, not
        // just tolerated by a lenient validator stub.
        assertThat(result.unmappedSourceColumns()).isEmpty();
    }

    @Test
    void modelCallThrows_failsCleanRatherThanCrashingUnhandled() {
        // The review's underlying concern (infrastructure failures
        // shouldn't masquerade as mapping-validation failures) is real
        // and still open -- see docs/local-llm-enhancements.md's
        // "External review, round 3" section for why the originally
        // attempted fix (a distinguishing catch for
        // org.springframework.ai.retry's exception types) had to be
        // reverted: that package doesn't exist in this project's actual
        // Spring AI 2.0.0 dependency at all, and broke the real build.
        // Until the correct real exception type (com.openai.errors.OpenAIException,
        // per real evidence, but not yet confirmed against an actual
        // compile) is confirmed, EVERY RuntimeException from the model
        // call -- infrastructure failure or conversion failure alike --
        // is treated the same way: a clean, reported validation
        // failure, not an unhandled crash. Imprecise, but not silently
        // broken -- this test proves the "not silently broken" half.
        Harness h = harness();
        JsonNode table = tableWithColumns("Valuation Px");
        when(h.fieldAliasResolver().resolve(any(), any(), any()))
                .thenReturn(new FieldAliasResolver.Result(List.of(), Set.of()));

        org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec requestSpec =
                mock(org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec.class);
        when(h.chatClient().prompt()).thenReturn(requestSpec);
        when(requestSpec.system(org.mockito.ArgumentMatchers.anyString())).thenReturn(requestSpec);
        when(requestSpec.user(org.mockito.ArgumentMatchers.anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenThrow(new RuntimeException("simulated: connection refused"));

        when(h.sumTypeResolver().resolve(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> new SumTypeMappingResolver.Result(inv.getArgument(0), List.of()));
        when(h.structuralValidator().validate(any(), any(), any()))
                .thenReturn(List.of("the proposal contains no field mappings at all"));
        when(h.structuralValidator().validateColumnCoverage(any(), any())).thenReturn(List.of());

        org.junit.jupiter.api.Assertions.assertThrows(MappingProposalValidationException.class,
                () -> h.service().propose(dummyModel(), dummyClient(), "f.xlsx", "Holdings", table));
    }

    /** Simulates the shape (specifically, the simple class name) of the
      * real exception a live run against the actual model stack threw
      * -- see docs/local-llm-enhancements.md for the full account --
      * without depending on openai-java actually being on this
      * project's test classpath under that exact name. Named to match
      * exactly, since the code under test distinguishes purely by
      * {@code getClass().getSimpleName()}, not by catching a specific
      * type. */
    private static class OpenAIIoException extends RuntimeException {
        OpenAIIoException(String message) {
            super(message);
        }
    }

    @Test
    void infrastructureShapedExceptionStillFailsCleanNotDifferently() {
        // The diagnostic-only improvement this round added: the log
        // message changes when getSimpleName() looks infrastructure-shaped,
        // but the actual control flow deliberately does not -- still a
        // clean MappingProposalValidationException, not a re-thrown
        // exception or a different HTTP outcome. That behavior-changing
        // fix remains real, open follow-up work (see the surrounding
        // code's own comment), not attempted in the same round as this
        // diagnostic change. This test is the proof the diagnostic
        // change didn't accidentally alter behavior along the way.
        Harness h = harness();
        JsonNode table = tableWithColumns("Valuation Px");
        when(h.fieldAliasResolver().resolve(any(), any(), any()))
                .thenReturn(new FieldAliasResolver.Result(List.of(), Set.of()));

        org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec requestSpec =
                mock(org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec.class);
        when(h.chatClient().prompt()).thenReturn(requestSpec);
        when(requestSpec.system(org.mockito.ArgumentMatchers.anyString())).thenReturn(requestSpec);
        when(requestSpec.user(org.mockito.ArgumentMatchers.anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenThrow(new OpenAIIoException("simulated: Request failed"));

        when(h.sumTypeResolver().resolve(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> new SumTypeMappingResolver.Result(inv.getArgument(0), List.of()));
        when(h.structuralValidator().validate(any(), any(), any()))
                .thenReturn(List.of("the proposal contains no field mappings at all"));
        when(h.structuralValidator().validateColumnCoverage(any(), any())).thenReturn(List.of());

        org.junit.jupiter.api.Assertions.assertThrows(MappingProposalValidationException.class,
                () -> h.service().propose(dummyModel(), dummyClient(), "f.xlsx", "Holdings", table));
    }
}
