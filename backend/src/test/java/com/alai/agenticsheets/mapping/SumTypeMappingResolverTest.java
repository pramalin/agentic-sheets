package com.alai.agenticsheets.mapping;

import com.alai.agenticsheets.canonical.CanonicalModel;
import com.alai.agenticsheets.canonical.CanonicalModelParser;
import com.alai.agenticsheets.canonical.ClientConfig;
import com.alai.agenticsheets.canonical.ClientModelConventions;
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
 * Steps LLM-2 and LLM-4 -- see {@code docs/local-llm-enhancements.md}).
 * Uses the real {@code canonical-models/holdings.yaml} test fixture,
 * whose {@code Currency} sum type has variants
 * {@code USD/EUR/GBP/JPY/CAD} and whose {@code AssetClass} sum type has
 * variants {@code Equity/FixedIncome/Cash/Alternative} -- the same shape
 * the 3B/7B/14B/32B benchmark in {@code docs/local-llm-evaluation.md}
 * was run against.
 *
 * <p>Most tests here use {@link #noConventions()} -- a client with no
 * configured vocabulary at all -- so they exercise exactly the Step
 * LLM-2 canonical-name-matching behavior unchanged. Step LLM-4's own
 * tests (see the bottom of this file) use {@link #withVariantValues}
 * to exercise the configured-vocabulary precedence rules specifically.
 */
class SumTypeMappingResolverTest {

    private final CanonicalModelParser parser = new CanonicalModelParser();

    private CanonicalModel holdings() throws Exception {
        Path file = Path.of(getClass().getClassLoader()
                .getResource("canonical-models/holdings.yaml").toURI());
        return parser.parse(file);
    }

    private ClientConfig noConventions() {
        return new ClientConfig("test-client", "yyyy-MM-dd", Map.of(), Map.of());
    }

    private ClientConfig withVariantValues(String modelId, String fieldPath, Map<String, String> variantValues) {
        ClientModelConventions conventions = new ClientModelConventions(Map.of(), Map.of(fieldPath, variantValues), List.of());
        return new ClientConfig("test-client", "yyyy-MM-dd", Map.of(), Map.of(modelId, conventions));
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

        SumTypeMappingResolver.Result result = new SumTypeMappingResolver(reader)
                .resolve(proposal, holdings(), noConventions(), "f.xlsx", "Holdings");

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

        SumTypeMappingResolver.Result result = new SumTypeMappingResolver(reader)
                .resolve(proposal, holdings(), noConventions(), "f.xlsx", "Holdings");

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

        SumTypeMappingResolver.Result result = new SumTypeMappingResolver(reader)
                .resolve(proposal, holdings(), noConventions(), "f.xlsx", "Holdings");

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

        SumTypeMappingResolver.Result result = new SumTypeMappingResolver(reader)
                .resolve(proposal, holdings(), noConventions(), "f.xlsx", "Holdings");

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

        SumTypeMappingResolver.Result result = new SumTypeMappingResolver(reader)
                .resolve(proposal, holdings(), noConventions(), "f.xlsx", "Holdings");

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

        SumTypeMappingResolver.Result result = new SumTypeMappingResolver(reader)
                .resolve(proposal, holdings(), noConventions(), "f.xlsx", "Holdings");

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

        SumTypeMappingResolver.Result result = new SumTypeMappingResolver(reader)
                .resolve(proposal, holdings(), noConventions(), "f.xlsx", "Holdings");

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

        SumTypeMappingResolver.Result result = new SumTypeMappingResolver(reader)
                .resolve(proposal, holdings(), noConventions(), "f.xlsx", "Holdings");

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

        SumTypeMappingResolver.Result result = new SumTypeMappingResolver(reader)
                .resolve(proposal, model, noConventions(), "f.xlsx", "Sheet1");

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

        SumTypeMappingResolver.Result result = new SumTypeMappingResolver(reader)
                .resolve(proposal, holdings(), noConventions(), "f.xlsx", "Holdings");

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

        SumTypeMappingResolver.Result result = new SumTypeMappingResolver(reader)
                .resolve(proposal, holdings(), noConventions(), "f.xlsx", "Holdings");

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

        SumTypeMappingResolver.Result result = new SumTypeMappingResolver(reader)
                .resolve(proposal, holdings(), noConventions(), "f.xlsx", "Holdings");

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

        new SumTypeMappingResolver(reader).resolve(proposal, holdings(), noConventions(), "f.xlsx", "Holdings");

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

        new SumTypeMappingResolver(reader).resolve(proposal, holdings(), noConventions(), "f.xlsx", "Holdings");

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

        SumTypeMappingResolver.Result result = new SumTypeMappingResolver(reader)
                .resolve(proposal, holdings(), noConventions(), "f.xlsx", "Holdings");

        assertThat(result.problems()).isEmpty();
        assertThat(result.proposal().fieldMappings().get(0).variantValueMap())
                .containsExactlyInAnyOrderEntriesOf(Map.of("USD", "USD", "EUR", "EUR"));
    }

    // =====================================================================
    // Step LLM-4: configured client vocabulary, consulted ahead of
    // canonical-name matching. See docs/local-llm-enhancements.md.
    // =====================================================================

    @Test
    void configuredVocabularyFillsAValueCanonicalMatchingAgrees_noOverrideNoted() throws Exception {
        // "Fixed Income" -> FixedIncome is what canonical-name matching
        // would ALSO produce (via normalization) -- configured and
        // canonical agree, so no CONFIGURED_OVERRIDE_NOTABLE.
        SpreadsheetRowReader reader = mock(SpreadsheetRowReader.class);
        when(reader.readAll("f.xlsx", "Holdings"))
                .thenReturn(rowsWithColumn("Asset Class", "Fixed Income"));

        MappingProposal proposal = new MappingProposal(
                List.of(mapping("asset_class", "Asset Class", null, null)), List.of(), "test");
        ClientConfig client = withVariantValues("Holdings", "asset_class", Map.of("Fixed Income", "FixedIncome"));

        SumTypeMappingResolver.Result result = new SumTypeMappingResolver(reader)
                .resolve(proposal, holdings(), client, "f.xlsx", "Holdings");

        assertThat(result.problems()).isEmpty();
        assertThat(result.proposal().fieldMappings().get(0).selectedVariant()).isEqualTo("FixedIncome");
    }

    @Test
    void configuredVocabularyWinsEvenWhenItDivergesFromCanonicalMatching_nonBlockingNote() throws Exception {
        // A deliberately unusual client code "FI" for FixedIncome --
        // canonical-name matching would never guess this from "FI" alone
        // (no unique match), so this also demonstrates configured
        // vocabulary resolving a value canonical-name matching alone
        // could not.
        SpreadsheetRowReader reader = mock(SpreadsheetRowReader.class);
        when(reader.readAll("f.xlsx", "Holdings")).thenReturn(rowsWithColumn("Asset Class", "FI"));

        MappingProposal proposal = new MappingProposal(
                List.of(mapping("asset_class", "Asset Class", null, null)), List.of(), "test");
        ClientConfig client = withVariantValues("Holdings", "asset_class", Map.of("FI", "FixedIncome"));

        SumTypeMappingResolver.Result result = new SumTypeMappingResolver(reader)
                .resolve(proposal, holdings(), client, "f.xlsx", "Holdings");

        assertThat(result.problems()).isEmpty(); // no canonical match exists to diverge from -- nothing to note
        assertThat(result.proposal().fieldMappings().get(0).selectedVariant()).isEqualTo("FixedIncome");
    }

    @Test
    void configuredVocabularyDivergesFromWhatCanonicalMatchingWouldPick_notableNonBlocking() throws Exception {
        // A client convention that deliberately maps a value AWAY from
        // what canonical-name matching alone would resolve it to --
        // the configured convention wins, but it's flagged as notable.
        SpreadsheetRowReader reader = mock(SpreadsheetRowReader.class);
        when(reader.readAll("f.xlsx", "Holdings")).thenReturn(rowsWithColumn("Currency", "USD"));

        MappingProposal proposal = new MappingProposal(
                List.of(mapping("currency", "Currency", null, null)), List.of(), "test");
        // Deliberately wrong-looking convention: "USD" literally equals a
        // real variant name, but this (synthetic, unrealistic) client
        // config maps it to EUR instead -- exercises that an explicit
        // convention is authoritative even when it disagrees with an
        // exact canonical-name match.
        ClientConfig client = withVariantValues("Holdings", "currency", Map.of("USD", "EUR"));

        SumTypeMappingResolver.Result result = new SumTypeMappingResolver(reader)
                .resolve(proposal, holdings(), client, "f.xlsx", "Holdings");

        assertThat(result.problems()).hasSize(1);
        MappingResolutionProblem problem = result.problems().get(0);
        assertThat(problem.kind()).isEqualTo(MappingResolutionProblem.Kind.CONFIGURED_OVERRIDE_NOTABLE);
        assertThat(problem.blocking()).isFalse();
        assertThat(problem.message()).contains("USD").contains("EUR");
        // The configured convention wins -- resolved to EUR, not USD.
        assertThat(result.proposal().fieldMappings().get(0).selectedVariant()).isEqualTo("EUR");
    }

    @Test
    void staleConfiguredTargetFailsClosed_doesNotFallBackToCanonicalMatching() throws Exception {
        // Simulates a canonical model change that removed a variant a
        // client's convention still references (defense in depth -- the
        // registry itself already validates this at load time per Step
        // LLM-3, but the resolver shouldn't trust a ClientConfig blindly
        // regardless of how it was obtained).
        SpreadsheetRowReader reader = mock(SpreadsheetRowReader.class);
        when(reader.readAll("f.xlsx", "Holdings")).thenReturn(rowsWithColumn("Currency", "USD"));

        MappingProposal proposal = new MappingProposal(
                List.of(mapping("currency", "Currency", null, null)), List.of(), "test");
        ClientConfig client = withVariantValues("Holdings", "currency", Map.of("USD", "NoSuchVariant"));

        SumTypeMappingResolver.Result result = new SumTypeMappingResolver(reader)
                .resolve(proposal, holdings(), client, "f.xlsx", "Holdings");

        assertThat(result.problems()).hasSize(1);
        MappingResolutionProblem problem = result.problems().get(0);
        assertThat(problem.kind()).isEqualTo(MappingResolutionProblem.Kind.CLIENT_CONFIGURATION);
        assertThat(problem.blocking()).isTrue();
        assertThat(problem.message()).contains("NoSuchVariant").contains("stale");
        // Fails closed -- not silently resolved via canonical-name
        // matching (which would have found USD -> USD).
        MappingProposal.FieldMapping unresolved = result.proposal().fieldMappings().get(0);
        assertThat(unresolved.selectedVariant()).isNull();
        assertThat(unresolved.variantValueMap()).isNull();
    }

    @Test
    void configuredVocabularyParticipatesInSelectedVariantCrossCheck() throws Exception {
        // An agent-supplied selectedVariant is checked against observed
        // data using configured vocabulary too, not just canonical-name
        // matching -- a configured alias for a code canonical matching
        // alone wouldn't resolve must still be recognized as consistent.
        SpreadsheetRowReader reader = mock(SpreadsheetRowReader.class);
        when(reader.readAll("f.xlsx", "Holdings")).thenReturn(rowsWithColumn("Asset Class", "FI", "FI"));

        MappingProposal proposal = new MappingProposal(
                List.of(mapping("asset_class", "Asset Class", "FixedIncome", null)), List.of(), "test");
        ClientConfig client = withVariantValues("Holdings", "asset_class", Map.of("FI", "FixedIncome"));

        SumTypeMappingResolver.Result result = new SumTypeMappingResolver(reader)
                .resolve(proposal, holdings(), client, "f.xlsx", "Holdings");

        // Without configured vocabulary, "FI" wouldn't canonical-match
        // anything, and this would incorrectly report a conflict.
        assertThat(result.problems()).isEmpty();
        assertThat(result.proposal().fieldMappings().get(0).selectedVariant()).isEqualTo("FixedIncome");
    }

    @Test
    void clientWithNoConventionsForThisModel_behavesExactlyLikeStepLlm2() throws Exception {
        // A client with conventions configured, but none for THIS
        // model -- must fall through to pure canonical-name matching,
        // not throw or behave differently from noConventions().
        SpreadsheetRowReader reader = mock(SpreadsheetRowReader.class);
        when(reader.readAll("f.xlsx", "Holdings")).thenReturn(rowsWithColumn("Currency", "USD"));

        MappingProposal proposal = new MappingProposal(
                List.of(mapping("currency", "Currency", null, null)), List.of(), "test");
        ClientConfig client = withVariantValues("SomeOtherModel", "someField", Map.of("x", "y"));

        SumTypeMappingResolver.Result result = new SumTypeMappingResolver(reader)
                .resolve(proposal, holdings(), client, "f.xlsx", "Holdings");

        assertThat(result.problems()).isEmpty();
        assertThat(result.proposal().fieldMappings().get(0).selectedVariant()).isEqualTo("USD");
    }

    // =====================================================================
    // External review finding (post Step LLM-6): validateVariantValueMap
    // only checked coverage, never whether the proposed TARGET agreed with
    // deterministic resolution. An authoritative configured convention
    // like USD -> USD would not have caught a model proposing
    // variantValueMap={"USD":"EUR"} as long as EUR was itself a legal
    // variant. See docs/local-llm-enhancements.md.
    // =====================================================================

    @Test
    void variantValueMapDisagreesWithAuthoritativeConfiguredVocabulary_semanticConflict() throws Exception {
        SpreadsheetRowReader reader = mock(SpreadsheetRowReader.class);
        when(reader.readAll("f.xlsx", "Holdings")).thenReturn(rowsWithColumn("Currency", "USD"));

        // The exact scenario the review flagged: an authoritative
        // convention says USD -> USD, but the model's own map proposes
        // USD -> EUR. EUR is a real variant, so coverage alone would
        // have passed this clean.
        Map<String, String> wrongMap = Map.of("USD", "EUR");
        MappingProposal proposal = new MappingProposal(
                List.of(mapping("currency", "Currency", null, wrongMap)), List.of(), "test");
        ClientConfig client = withVariantValues("Holdings", "currency", Map.of("USD", "USD"));

        SumTypeMappingResolver.Result result = new SumTypeMappingResolver(reader)
                .resolve(proposal, holdings(), client, "f.xlsx", "Holdings");

        assertThat(result.problems()).hasSize(1);
        MappingResolutionProblem problem = result.problems().get(0);
        assertThat(problem.kind()).isEqualTo(MappingResolutionProblem.Kind.SEMANTIC_CONFLICT);
        assertThat(problem.blocking()).isTrue();
        assertThat(problem.message()).contains("USD").contains("EUR").contains("disagrees");
        // Not repaired -- left exactly as proposed, same non-repair
        // policy as every other semantic-conflict case.
        assertThat(result.proposal().fieldMappings().get(0).variantValueMap()).isEqualTo(wrongMap);
    }

    @Test
    void variantValueMapAgreesWithCanonicalMatching_noConflict() throws Exception {
        SpreadsheetRowReader reader = mock(SpreadsheetRowReader.class);
        when(reader.readAll("f.xlsx", "Holdings"))
                .thenReturn(rowsWithColumn("Asset Class", "Equity", "Fixed Income"));

        Map<String, String> correctMap = Map.of("Equity", "Equity", "Fixed Income", "FixedIncome");
        MappingProposal proposal = new MappingProposal(
                List.of(mapping("asset_class", "Asset Class", null, correctMap)), List.of(), "test");

        SumTypeMappingResolver.Result result = new SumTypeMappingResolver(reader)
                .resolve(proposal, holdings(), noConventions(), "f.xlsx", "Holdings");

        assertThat(result.problems()).isEmpty();
        assertThat(result.proposal().fieldMappings().get(0).variantValueMap()).isEqualTo(correctMap);
    }

    @Test
    void variantValueMapValueWithNoDeterministicAnswer_leftForHumanReview() throws Exception {
        // "Bitcoin" doesn't canonical-match anything and has no
        // configured entry -- resolveValue returns empty with no
        // problem of its own. Per the review's own recommended fix: if
        // there's no deterministic answer to disagree with, the model's
        // own mapping stays as proposed rather than being flagged wrong.
        SpreadsheetRowReader reader = mock(SpreadsheetRowReader.class);
        when(reader.readAll("f.xlsx", "Holdings")).thenReturn(rowsWithColumn("Currency", "Bitcoin"));

        Map<String, String> modelsGuess = Map.of("Bitcoin", "USD");
        MappingProposal proposal = new MappingProposal(
                List.of(mapping("currency", "Currency", null, modelsGuess)), List.of(), "test");

        SumTypeMappingResolver.Result result = new SumTypeMappingResolver(reader)
                .resolve(proposal, holdings(), noConventions(), "f.xlsx", "Holdings");

        assertThat(result.problems()).isEmpty();
        assertThat(result.proposal().fieldMappings().get(0).variantValueMap()).isEqualTo(modelsGuess);
    }

    @Test
    void variantValueMapMismatchAgainstStaleConfiguredEntry_reportsConfigProblemNotMismatch() throws Exception {
        // A stale configured target still fails closed via
        // CLIENT_CONFIGURATION (resolveValue's own job); since that means
        // no deterministic answer was actually produced, the mismatch
        // check must not ALSO fire a redundant/misleading second problem
        // claiming the model's target was "wrong" when there was no
        // known-correct target to compare against.
        SpreadsheetRowReader reader = mock(SpreadsheetRowReader.class);
        when(reader.readAll("f.xlsx", "Holdings")).thenReturn(rowsWithColumn("Currency", "USD"));

        Map<String, String> modelsMap = Map.of("USD", "EUR");
        MappingProposal proposal = new MappingProposal(
                List.of(mapping("currency", "Currency", null, modelsMap)), List.of(), "test");
        ClientConfig client = withVariantValues("Holdings", "currency", Map.of("USD", "NoSuchVariant"));

        SumTypeMappingResolver.Result result = new SumTypeMappingResolver(reader)
                .resolve(proposal, holdings(), client, "f.xlsx", "Holdings");

        assertThat(result.problems()).hasSize(1);
        assertThat(result.problems().get(0).kind()).isEqualTo(MappingResolutionProblem.Kind.CLIENT_CONFIGURATION);
    }

    // =====================================================================
    // External review finding (post Step LLM-6): a null element WITHIN a
    // non-null fieldMappings list -- distinct from the null/empty LIST
    // cases MappingProposal's compact constructor already handles. The
    // real schema-echo finding proved malformed structured output isn't
    // theoretical. See docs/local-llm-enhancements.md.
    // =====================================================================

    @Test
    void nullFieldMappingElement_doesNotCrashTheResolver() throws Exception {
        SpreadsheetRowReader reader = mock(SpreadsheetRowReader.class);
        when(reader.readAll("f.xlsx", "Holdings")).thenReturn(rowsWithColumn("Currency", "USD"));

        List<MappingProposal.FieldMapping> withNull = new java.util.ArrayList<>();
        withNull.add(null);
        withNull.add(mapping("currency", "Currency", null, null));
        MappingProposal proposal = new MappingProposal(withNull, List.of(), "test");

        SumTypeMappingResolver.Result result = new SumTypeMappingResolver(reader)
                .resolve(proposal, holdings(), noConventions(), "f.xlsx", "Holdings");

        // No crash -- the null element passes through unchanged; the
        // real (non-null) sum-type entry still resolves normally.
        assertThat(result.proposal().fieldMappings()).hasSize(2);
        assertThat(result.proposal().fieldMappings().get(0)).isNull();
        assertThat(result.proposal().fieldMappings().get(1).selectedVariant()).isEqualTo("USD");
    }

    @Test
    void nullFieldMappingElement_doesNotBreakHasSumTypeMappingDetection() throws Exception {
        // A null element must not throw while checking whether ANY entry
        // is a sum-type field, nor prevent a later real entry from being
        // detected and read (i.e. the row read must still happen).
        SpreadsheetRowReader reader = mock(SpreadsheetRowReader.class);
        when(reader.readAll("f.xlsx", "Holdings")).thenReturn(rowsWithColumn("Currency", "USD"));

        List<MappingProposal.FieldMapping> withNullFirst = new java.util.ArrayList<>();
        withNullFirst.add(null);
        withNullFirst.add(mapping("currency", "Currency", null, null));
        MappingProposal proposal = new MappingProposal(withNullFirst, List.of(), "test");

        new SumTypeMappingResolver(reader).resolve(proposal, holdings(), noConventions(), "f.xlsx", "Holdings");

        verify(reader, times(1)).readAll("f.xlsx", "Holdings");
    }
}
