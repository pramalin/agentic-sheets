package com.alai.agenticsheets.mapping;

import com.alai.agenticsheets.canonical.CanonicalModel;
import com.alai.agenticsheets.canonical.CanonicalModelParser;
import com.alai.agenticsheets.canonical.RecordType;
import com.alai.agenticsheets.canonical.SumType;
import com.alai.agenticsheets.canonical.TargetConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Acceptance tests for {@link SumTypeMappingResolver} (Local LLM phase,
 * Step LLM-2 -- see {@code docs/local-llm-enhancements.md}). Uses the
 * real {@code canonical-models/holdings.yaml} test fixture, whose
 * {@code Currency} sum type has variants {@code USD/EUR/GBP/JPY/CAD} and
 * whose {@code AssetClass} sum type has variants
 * {@code Equity/FixedIncome/Cash/Alternative} -- the same shape the
 * 3B/7B/14B/32B benchmark in {@code docs/local-llm-evaluation.md} was run
 * against.
 */
class SumTypeMappingResolverTest {

    private final CanonicalModelParser parser = new CanonicalModelParser();

    private CanonicalModel holdings() throws Exception {
        Path file = Path.of(getClass().getClassLoader()
                .getResource("canonical-models/holdings.yaml").toURI());
        return parser.parse(file);
    }

    private MappingProposal.FieldMapping mapping(String path, String sourceColumn, String selectedVariant,
            Map<String, String> variantValueMap) {
        return new MappingProposal.FieldMapping(path, sourceColumn, null, selectedVariant, variantValueMap,
                null, 0.9, "");
    }

    private List<Map<String, String>> rowsWithColumn(String column, String... values) {
        return java.util.Arrays.stream(values)
                .map(v -> {
                    java.util.Map<String, String> row = new java.util.LinkedHashMap<>();
                    row.put(column, v);
                    return (Map<String, String>) row;
                })
                .toList();
    }

    // --- Core enrichment: missing + uniquely derivable -> fill ---

    @Test
    void currencyAllUsd_fillsSelectedVariant() throws Exception {
        SpreadsheetRowReader reader = mock(SpreadsheetRowReader.class);
        when(reader.readAll("f.xlsx", "Holdings"))
                .thenReturn(rowsWithColumn("Currency", "USD", "USD", "USD"));

        MappingProposal proposal = new MappingProposal(
                List.of(mapping("currency", "Currency", null, null)), List.of(), "test");

        SumTypeMappingResolver.Result result =
                new SumTypeMappingResolver(reader).resolve(proposal, holdings(), "f.xlsx", "Holdings");

        assertThat(result.problems()).isEmpty();
        MappingProposal.FieldMapping resolved = result.proposal().fieldMappings().get(0);
        assertThat(resolved.selectedVariant()).isEqualTo("USD");
        assertThat(resolved.variantValueMap()).isNull();
        // Every other property preserved.
        assertThat(resolved.sourceColumn()).isEqualTo("Currency");
        assertThat(resolved.confidence()).isEqualTo(0.9);
    }

    @Test
    void assetClassMixed_fillsCompleteVariantValueMap() throws Exception {
        SpreadsheetRowReader reader = mock(SpreadsheetRowReader.class);
        when(reader.readAll("f.xlsx", "Holdings"))
                .thenReturn(rowsWithColumn("Asset Class", "Equity", "Fixed Income", "Equity"));

        MappingProposal proposal = new MappingProposal(
                List.of(mapping("asset_class", "Asset Class", null, null)), List.of(), "test");

        SumTypeMappingResolver.Result result =
                new SumTypeMappingResolver(reader).resolve(proposal, holdings(), "f.xlsx", "Holdings");

        assertThat(result.problems()).isEmpty();
        MappingProposal.FieldMapping resolved = result.proposal().fieldMappings().get(0);
        assertThat(resolved.selectedVariant()).isNull();
        assertThat(resolved.variantValueMap()).containsExactlyInAnyOrderEntriesOf(
                Map.of("Equity", "Equity", "Fixed Income", "FixedIncome"));
    }

    // --- Preserve valid existing metadata ---

    @Test
    void existingCorrectSelectedVariant_preservedUnchanged() throws Exception {
        SpreadsheetRowReader reader = mock(SpreadsheetRowReader.class);
        when(reader.readAll("f.xlsx", "Holdings"))
                .thenReturn(rowsWithColumn("Currency", "USD", "USD"));

        MappingProposal proposal = new MappingProposal(
                List.of(mapping("currency", "Currency", "USD", null)), List.of(), "test");

        SumTypeMappingResolver.Result result =
                new SumTypeMappingResolver(reader).resolve(proposal, holdings(), "f.xlsx", "Holdings");

        assertThat(result.problems()).isEmpty();
        assertThat(result.proposal().fieldMappings().get(0).selectedVariant()).isEqualTo("USD");
    }

    @Test
    void existingCorrectCompleteVariantValueMap_preservedUnchanged() throws Exception {
        SpreadsheetRowReader reader = mock(SpreadsheetRowReader.class);
        when(reader.readAll("f.xlsx", "Holdings"))
                .thenReturn(rowsWithColumn("Asset Class", "Equity", "Fixed Income"));

        Map<String, String> map = Map.of("Equity", "Equity", "Fixed Income", "FixedIncome");
        MappingProposal proposal = new MappingProposal(
                List.of(mapping("asset_class", "Asset Class", null, map)), List.of(), "test");

        SumTypeMappingResolver.Result result =
                new SumTypeMappingResolver(reader).resolve(proposal, holdings(), "f.xlsx", "Holdings");

        assertThat(result.problems()).isEmpty();
        assertThat(result.proposal().fieldMappings().get(0).variantValueMap()).isEqualTo(map);
    }

    // --- Semantic conflicts: existing metadata contradicts observed data ---

    @Test
    void selectedVariantEquity_butRowsContainFixedIncomeToo_semanticConflict() throws Exception {
        // The exact 3B benchmark failure mode: selectedVariant=Equity
        // proposed against a column that actually contains mixed values.
        SpreadsheetRowReader reader = mock(SpreadsheetRowReader.class);
        when(reader.readAll("f.xlsx", "Holdings"))
                .thenReturn(rowsWithColumn("Asset Class", "Equity", "Fixed Income"));

        MappingProposal proposal = new MappingProposal(
                List.of(mapping("asset_class", "Asset Class", "Equity", null)), List.of(), "test");

        SumTypeMappingResolver.Result result =
                new SumTypeMappingResolver(reader).resolve(proposal, holdings(), "f.xlsx", "Holdings");

        assertThat(result.problems()).hasSize(1);
        MappingResolutionProblem problem = result.problems().get(0);
        assertThat(problem.kind()).isEqualTo(MappingResolutionProblem.Kind.SEMANTIC_CONFLICT);
        assertThat(problem.blocking()).isTrue();
        assertThat(problem.canonicalFieldPath()).isEqualTo("asset_class");
        // Not repaired -- left exactly as proposed.
        assertThat(result.proposal().fieldMappings().get(0).selectedVariant()).isEqualTo("Equity");
    }

    @Test
    void incompleteVariantValueMap_missingObservedValue_semanticConflict() throws Exception {
        SpreadsheetRowReader reader = mock(SpreadsheetRowReader.class);
        when(reader.readAll("f.xlsx", "Holdings"))
                .thenReturn(rowsWithColumn("Asset Class", "Equity", "Fixed Income", "Cash"));

        Map<String, String> incompleteMap = Map.of("Equity", "Equity", "Fixed Income", "FixedIncome");
        MappingProposal proposal = new MappingProposal(
                List.of(mapping("asset_class", "Asset Class", null, incompleteMap)), List.of(), "test");

        SumTypeMappingResolver.Result result =
                new SumTypeMappingResolver(reader).resolve(proposal, holdings(), "f.xlsx", "Holdings");

        assertThat(result.problems()).hasSize(1);
        assertThat(result.problems().get(0).kind()).isEqualTo(MappingResolutionProblem.Kind.SEMANTIC_CONFLICT);
        assertThat(result.problems().get(0).message()).contains("Cash");
        // Not repaired.
        assertThat(result.proposal().fieldMappings().get(0).variantValueMap()).isEqualTo(incompleteMap);
    }

    // --- Fail closed: never guess ---

    @Test
    void unknownSourceValue_doesNotInventAMapping_leftUnresolved() throws Exception {
        SpreadsheetRowReader reader = mock(SpreadsheetRowReader.class);
        when(reader.readAll("f.xlsx", "Holdings"))
                .thenReturn(rowsWithColumn("Currency", "USD", "Bitcoin"));

        MappingProposal proposal = new MappingProposal(
                List.of(mapping("currency", "Currency", null, null)), List.of(), "test");

        SumTypeMappingResolver.Result result =
                new SumTypeMappingResolver(reader).resolve(proposal, holdings(), "f.xlsx", "Holdings");

        assertThat(result.problems()).hasSize(1);
        assertThat(result.problems().get(0).kind()).isEqualTo(MappingResolutionProblem.Kind.UNRESOLVED);
        assertThat(result.problems().get(0).message()).contains("Bitcoin");
        MappingProposal.FieldMapping unresolved = result.proposal().fieldMappings().get(0);
        assertThat(unresolved.selectedVariant()).isNull();
        assertThat(unresolved.variantValueMap()).isNull();
    }

    @Test
    void allBlankSourceColumn_noInformationToDeriveFrom_leftUnresolved() throws Exception {
        SpreadsheetRowReader reader = mock(SpreadsheetRowReader.class);
        when(reader.readAll("f.xlsx", "Holdings"))
                .thenReturn(rowsWithColumn("Currency", "", "  ", null));

        MappingProposal proposal = new MappingProposal(
                List.of(mapping("currency", "Currency", null, null)), List.of(), "test");

        SumTypeMappingResolver.Result result =
                new SumTypeMappingResolver(reader).resolve(proposal, holdings(), "f.xlsx", "Holdings");

        assertThat(result.problems()).hasSize(1);
        assertThat(result.problems().get(0).kind()).isEqualTo(MappingResolutionProblem.Kind.UNRESOLVED);
    }

    @Test
    void ambiguousNormalizedVariants_doesNotChoose() throws Exception {
        // Synthetic model where two variant names collide after
        // normalization ("F X" and "F-X" both normalize to "fx") --
        // exercises the tie-breaking rule directly, since no real
        // canonical model fixture happens to have colliding variant names.
        SumType ambiguousSumType = new SumType("Ambiguous", Map.of(
                "F X", new RecordType("Ambiguous.F X", Map.of()),
                "F-X", new RecordType("Ambiguous.F-X", Map.of())));
        RecordType root = new RecordType("Root", Map.of("code", ambiguousSumType));
        CanonicalModel model = new CanonicalModel("Test", 1,
                new TargetConfig("svc", "rest", "http://x", null, "api-key", "SECRET",
                        com.alai.agenticsheets.canonical.DeliveryConfig.defaults()),
                root, Map.of(), Path.of("test.yaml"));

        SpreadsheetRowReader reader = mock(SpreadsheetRowReader.class);
        when(reader.readAll("f.xlsx", "Sheet1")).thenReturn(rowsWithColumn("Code", "fx"));

        MappingProposal proposal = new MappingProposal(
                List.of(mapping("code", "Code", null, null)), List.of(), "test");

        SumTypeMappingResolver.Result result =
                new SumTypeMappingResolver(reader).resolve(proposal, model, "f.xlsx", "Sheet1");

        assertThat(result.problems()).hasSize(1);
        assertThat(result.problems().get(0).kind()).isEqualTo(MappingResolutionProblem.Kind.UNRESOLVED);
        assertThat(result.proposal().fieldMappings().get(0).selectedVariant()).isNull();
    }

    @Test
    void trailingWhitespaceInValue_stillMatchesExactly() throws Exception {
        SpreadsheetRowReader reader = mock(SpreadsheetRowReader.class);
        when(reader.readAll("f.xlsx", "Holdings"))
                .thenReturn(rowsWithColumn("Currency", "USD ", " USD"));

        MappingProposal proposal = new MappingProposal(
                List.of(mapping("currency", "Currency", null, null)), List.of(), "test");

        SumTypeMappingResolver.Result result =
                new SumTypeMappingResolver(reader).resolve(proposal, holdings(), "f.xlsx", "Holdings");

        assertThat(result.problems()).isEmpty();
        assertThat(result.proposal().fieldMappings().get(0).selectedVariant()).isEqualTo("USD");
    }

    // --- Never repair structurally contradictory proposals ---

    @Test
    void selectedVariantAndVariantValueMapBothSet_notRepaired_leftForStructuralValidator() throws Exception {
        SpreadsheetRowReader reader = mock(SpreadsheetRowReader.class);
        when(reader.readAll("f.xlsx", "Holdings")).thenReturn(rowsWithColumn("Currency", "USD"));

        Map<String, String> map = Map.of("USD", "USD");
        MappingProposal proposal = new MappingProposal(
                List.of(mapping("currency", "Currency", "USD", map)), List.of(), "test");

        SumTypeMappingResolver.Result result =
                new SumTypeMappingResolver(reader).resolve(proposal, holdings(), "f.xlsx", "Holdings");

        assertThat(result.problems()).isEmpty(); // resolver adds nothing; structural validator's job
        MappingProposal.FieldMapping unchanged = result.proposal().fieldMappings().get(0);
        assertThat(unchanged.selectedVariant()).isEqualTo("USD");
        assertThat(unchanged.variantValueMap()).isEqualTo(map);
    }

    // --- Non-sum fields untouched ---

    @Test
    void nonSumFields_completelyUntouched() throws Exception {
        SpreadsheetRowReader reader = mock(SpreadsheetRowReader.class);
        when(reader.readAll("f.xlsx", "Holdings")).thenReturn(rowsWithColumn("Currency", "USD"));

        MappingProposal.FieldMapping primitive = new MappingProposal.FieldMapping(
                "account_id", "Account", null, null, null, null, 0.95, "");
        MappingProposal proposal = new MappingProposal(
                List.of(primitive, mapping("currency", "Currency", null, null)), List.of(), "test");

        SumTypeMappingResolver.Result result =
                new SumTypeMappingResolver(reader).resolve(proposal, holdings(), "f.xlsx", "Holdings");

        MappingProposal.FieldMapping untouchedPrimitive = result.proposal().fieldMappings().stream()
                .filter(fm -> fm.canonicalFieldPath().equals("account_id"))
                .findFirst().orElseThrow();
        assertThat(untouchedPrimitive).isEqualTo(primitive);
    }

    @Test
    void noSumTypeMappingsAtAll_neverReadsRows() throws Exception {
        SpreadsheetRowReader reader = mock(SpreadsheetRowReader.class);

        MappingProposal.FieldMapping primitive = new MappingProposal.FieldMapping(
                "account_id", "Account", null, null, null, null, 0.95, "");
        MappingProposal proposal = new MappingProposal(List.of(primitive), List.of(), "test");

        new SumTypeMappingResolver(reader).resolve(proposal, holdings(), "f.xlsx", "Holdings");

        verify(reader, never()).readAll(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    // --- Reads rows exactly once regardless of how many sum-type fields exist ---

    @Test
    void multipleSumTypeFields_readsRowsExactlyOnce() throws Exception {
        SpreadsheetRowReader reader = mock(SpreadsheetRowReader.class);
        when(reader.readAll("f.xlsx", "Holdings")).thenReturn(List.of(
                Map.of("Currency", "USD", "Asset Class", "Equity")));

        MappingProposal proposal = new MappingProposal(
                List.of(mapping("currency", "Currency", null, null),
                        mapping("asset_class", "Asset Class", null, null)),
                List.of(), "test");

        new SumTypeMappingResolver(reader).resolve(proposal, holdings(), "f.xlsx", "Holdings");

        verify(reader, times(1)).readAll("f.xlsx", "Holdings");
    }

    // --- Large row set: resolver doesn't paginate itself, trusts the reader ---

    @Test
    void largeRowSet_aggregatesEveryDistinctValueFromWhatTheReaderReturns() throws Exception {
        SpreadsheetRowReader reader = mock(SpreadsheetRowReader.class);
        List<Map<String, String>> manyRows = new java.util.ArrayList<>();
        for (int i = 0; i < 510; i++) {
            manyRows.add(Map.of("Currency", i < 505 ? "USD" : "EUR"));
        }
        when(reader.readAll("f.xlsx", "Holdings")).thenReturn(manyRows);

        MappingProposal proposal = new MappingProposal(
                List.of(mapping("currency", "Currency", null, null)), List.of(), "test");

        SumTypeMappingResolver.Result result =
                new SumTypeMappingResolver(reader).resolve(proposal, holdings(), "f.xlsx", "Holdings");

        assertThat(result.problems()).isEmpty();
        assertThat(result.proposal().fieldMappings().get(0).variantValueMap())
                .containsExactlyInAnyOrderEntriesOf(Map.of("USD", "USD", "EUR", "EUR"));
    }
}
