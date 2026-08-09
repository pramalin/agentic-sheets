package com.alai.agenticsheets.mapping;

import com.alai.agenticsheets.canonical.CanonicalModel;
import com.alai.agenticsheets.canonical.CanonicalModelParser;
import com.alai.agenticsheets.canonical.ClientConfig;
import com.alai.agenticsheets.canonical.ClientModelConventions;
import com.alai.agenticsheets.canonical.PrimitiveType;
import com.alai.agenticsheets.canonical.RecordType;
import com.alai.agenticsheets.canonical.TargetConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance tests for {@link FieldAliasResolver} -- Local LLM phase,
 * Step LLM-4's originally-deferred piece, finally built following an
 * external review's Finding 6, then corrected in a second review round
 * (see {@code docs/local-llm-enhancements.md}). Uses the real
 * {@code canonical-models/holdings.yaml} test fixture -- the same one
 * every resolver test in this phase has used.
 *
 * <p>As of the second review round, canonical model {@code synonyms}
 * are deliberately NOT one of this resolver's deterministic sources
 * (see {@link FieldAliasResolver}'s own javadoc for the full reasoning)
 * -- only a field's own name and configured client aliases are. Several
 * tests here exist specifically to prove that reversal, not just to
 * test the current (narrower) positive behavior in isolation.
 */
class FieldAliasResolverTest {

    private final CanonicalModelParser parser = new CanonicalModelParser();
    private final FieldAliasResolver resolver = new FieldAliasResolver();

    private CanonicalModel holdings() throws Exception {
        Path file = Path.of(getClass().getClassLoader()
                .getResource("canonical-models/holdings.yaml").toURI());
        return parser.parse(file);
    }

    private ClientConfig noConventions() {
        return new ClientConfig("test-client", "yyyy-MM-dd", Map.of(), Map.of());
    }

    private ClientConfig withFieldAliases(String modelId, String fieldPath, List<String> aliases) {
        ClientModelConventions conventions = new ClientModelConventions(Map.of(fieldPath, aliases), Map.of());
        return new ClientConfig("test-client", "yyyy-MM-dd", Map.of(), Map.of(modelId, conventions));
    }

    @Test
    void resolvesAColumnMatchingTheFieldsOwnName() throws Exception {
        // "Currency" (the real fixture's literal header) normalizes the
        // same as the canonical field's own name "currency" -- no
        // alias needed at all.
        FieldAliasResolver.Result result =
                resolver.resolve(holdings(), noConventions(), Set.of("Currency"));

        assertThat(result.resolvedSourceColumns()).containsExactly("Currency");
        MappingProposal.FieldMapping fm = result.resolvedMappings().get(0);
        assertThat(fm.canonicalFieldPath()).isEqualTo("currency");
        assertThat(fm.sourceColumn()).isEqualTo("Currency");
        assertThat(fm.selectedVariant()).isNull();
        assertThat(fm.variantValueMap()).isNull();
    }

    @Test
    void canonicalSynonymsAreDeliberatelyNotDeterministic() throws Exception {
        // "CUSIP" is a real entry in holdings.yaml's own synonyms:
        // block for security_id -- but as of the correction following a
        // second external review round, canonical synonyms are treated
        // as LLM hints only (matching canonical-models/SCHEMA.md's own
        // pre-existing, documented intent), never deterministic. This
        // is the reversal itself, proven directly: "CUSIP" must be left
        // unresolved for the LLM, not silently matched.
        FieldAliasResolver.Result result =
                resolver.resolve(holdings(), noConventions(), Set.of("CUSIP"));

        assertThat(result.resolvedMappings()).isEmpty();
        assertThat(result.resolvedSourceColumns()).isEmpty();
    }

    @Test
    void aClientCanExplicitlyPromoteAFormerSynonymToADeterministicAlias() throws Exception {
        // The correct way to make "CUSIP" deterministic again, if a
        // model/client owner actually wants that: configure it as an
        // explicit, human-approved client alias (Step LLM-3) -- not by
        // relying on the canonical model's own synonyms list.
        ClientConfig client = withFieldAliases("Holdings", "security_id", List.of("CUSIP"));

        FieldAliasResolver.Result result = resolver.resolve(holdings(), client, Set.of("CUSIP"));

        assertThat(result.resolvedSourceColumns()).containsExactly("CUSIP");
        assertThat(result.resolvedMappings().get(0).canonicalFieldPath()).isEqualTo("security_id");
    }

    @Test
    void resolvesAColumnMatchingAConfiguredClientAlias() throws Exception {
        // "Val Px" is not the field's own name -- purely a
        // client-specific convention, unrelated to any canonical
        // synonym.
        ClientConfig client = withFieldAliases("Holdings", "market_price", List.of("Val Px"));

        FieldAliasResolver.Result result = resolver.resolve(holdings(), client, Set.of("Val Px"));

        assertThat(result.resolvedSourceColumns()).containsExactly("Val Px");
        assertThat(result.resolvedMappings().get(0).canonicalFieldPath()).isEqualTo("market_price");
    }

    @Test
    void resolvesASumTypeFieldsColumnWithoutTouchingVariantMechanics() throws Exception {
        // "Asset Class" matches asset_class's own name (normalized) --
        // the resolved FieldMapping must have neither selectedVariant
        // nor variantValueMap set; that's SumTypeMappingResolver's job,
        // which still runs afterward on the merged proposal.
        FieldAliasResolver.Result result =
                resolver.resolve(holdings(), noConventions(), Set.of("Asset Class"));

        assertThat(result.resolvedSourceColumns()).containsExactly("Asset Class");
        MappingProposal.FieldMapping fm = result.resolvedMappings().get(0);
        assertThat(fm.canonicalFieldPath()).isEqualTo("asset_class");
        assertThat(fm.selectedVariant()).isNull();
        assertThat(fm.variantValueMap()).isNull();
    }

    @Test
    void clientIdIsNeverACandidateEvenWithAConfiguredAlias() throws Exception {
        // client_id is resolved externally, before this resolver or the
        // LLM is ever involved -- a configured alias for it must be
        // ignored, not accidentally honored.
        ClientConfig client = withFieldAliases("Holdings", "client_id", List.of("Client"));

        FieldAliasResolver.Result result = resolver.resolve(holdings(), client, Set.of("Client"));

        assertThat(result.resolvedMappings()).isEmpty();
        assertThat(result.resolvedSourceColumns()).isEmpty();
    }

    @Test
    void unmatchedColumnIsLeftAloneForTheLlm() throws Exception {
        FieldAliasResolver.Result result =
                resolver.resolve(holdings(), noConventions(), Set.of("Valuation Px"));

        assertThat(result.resolvedMappings()).isEmpty();
        assertThat(result.resolvedSourceColumns()).isEmpty();
    }

    @Test
    void multipleResolvableColumnsAllResolveIndependently() throws Exception {
        // All three resolve via the field's own name -- "CUSIP" is
        // deliberately NOT included here (see
        // canonicalSynonymsAreDeliberatelyNotDeterministic).
        FieldAliasResolver.Result result = resolver.resolve(holdings(), noConventions(),
                Set.of("Currency", "Custodian", "Market Value", "Valuation Px"));

        assertThat(result.resolvedSourceColumns())
                .containsExactlyInAnyOrder("Currency", "Custodian", "Market Value");
        assertThat(result.resolvedMappings()).hasSize(3);
        assertThat(result.resolvedMappings())
                .extracting(MappingProposal.FieldMapping::canonicalFieldPath)
                .containsExactlyInAnyOrder("currency", "custodian", "market_value");
    }

    @Test
    void ambiguousCandidateAcrossTwoDifferentFieldsIsNotResolvedForEither() throws Exception {
        // Two different fields' CONFIGURED CLIENT ALIASES (not
        // synonyms, which this resolver no longer consults at all)
        // collide after normalization -- exercises the ambiguity rule
        // on the one remaining source capable of producing it, since a
        // real field's own literal name can't collide with another
        // field's own literal name (canonical field paths are already
        // unique by construction).
        RecordType root = new RecordType("Root", Map.of(
                "field_a", new PrimitiveType(PrimitiveType.Kind.STRING, null),
                "field_b", new PrimitiveType(PrimitiveType.Kind.STRING, null)));
        CanonicalModel model = new CanonicalModel("Test", 1,
                new TargetConfig("svc", "rest", "http://x", null, "api-key", "SECRET",
                        com.alai.agenticsheets.canonical.DeliveryConfig.defaults()),
                root, Map.of(), Path.of("test.yaml"));

        ClientModelConventions conventions = new ClientModelConventions(
                Map.of("field_a", List.of("shared name"), "field_b", List.of("shared-name")), Map.of());
        ClientConfig client = new ClientConfig("test-client", "yyyy-MM-dd", Map.of(), Map.of("Test", conventions));

        FieldAliasResolver.Result result = resolver.resolve(model, client, Set.of("Shared Name"));

        assertThat(result.resolvedMappings()).isEmpty();
        assertThat(result.resolvedSourceColumns()).isEmpty();
    }

    @Test
    void variantQualifiedSubFieldPathsAreNeverCandidates() throws Exception {
        // FixedIncome's maturity_date lives at
        // "asset_class.FixedIncome.maturity_date" -- a dotted,
        // variant-qualified path this resolver deliberately never
        // considers, even if a column header happened to normalize the
        // same as the field's own leaf name.
        FieldAliasResolver.Result result =
                resolver.resolve(holdings(), noConventions(), Set.of("Maturity Date"));

        assertThat(result.resolvedMappings()).isEmpty();
        assertThat(result.resolvedSourceColumns()).isEmpty();
    }
}
