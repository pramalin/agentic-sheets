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
 * external review's Finding 6 (see {@code docs/local-llm-enhancements.md}).
 * Uses the real {@code canonical-models/holdings.yaml} test fixture --
 * the same one every resolver test in this phase has used -- whose real
 * {@code synonyms:} block covers every primitive field (confirmed by
 * reading the actual file, not assumed) but deliberately does not cover
 * {@code asset_class} (a sum type, matched via its own field name
 * instead) or {@code client_id} (never a candidate at all, resolved
 * externally).
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
        // synonym or alias needed at all.
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
    void resolvesAColumnMatchingACanonicalModelSynonym() throws Exception {
        // "CUSIP" is a real synonym for security_id in holdings.yaml's
        // own synonyms block -- not the field's own name at all.
        FieldAliasResolver.Result result =
                resolver.resolve(holdings(), noConventions(), Set.of("CUSIP"));

        assertThat(result.resolvedSourceColumns()).containsExactly("CUSIP");
        assertThat(result.resolvedMappings().get(0).canonicalFieldPath()).isEqualTo("security_id");
    }

    @Test
    void resolvesAColumnMatchingAConfiguredClientAlias() throws Exception {
        // "Val Px" is not the field's own name and not a canonical
        // synonym -- purely a client-specific convention.
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
        FieldAliasResolver.Result result = resolver.resolve(holdings(), noConventions(),
                Set.of("Currency", "CUSIP", "Custodian", "Valuation Px"));

        assertThat(result.resolvedSourceColumns()).containsExactlyInAnyOrder("Currency", "CUSIP", "Custodian");
        assertThat(result.resolvedMappings()).hasSize(3);
        assertThat(result.resolvedMappings())
                .extracting(MappingProposal.FieldMapping::canonicalFieldPath)
                .containsExactlyInAnyOrder("currency", "security_id", "custodian");
    }

    @Test
    void ambiguousCandidateAcrossTwoDifferentFieldsIsNotResolvedForEither() throws Exception {
        // A synthetic model where two different fields' synonym lists
        // collide after normalization -- exercises the ambiguity rule
        // directly, since no real fixture happens to have colliding
        // synonyms.
        RecordType root = new RecordType("Root", Map.of(
                "field_a", new PrimitiveType(PrimitiveType.Kind.STRING, null),
                "field_b", new PrimitiveType(PrimitiveType.Kind.STRING, null)));
        CanonicalModel model = new CanonicalModel("Test", 1,
                new TargetConfig("svc", "rest", "http://x", null, "api-key", "SECRET",
                        com.alai.agenticsheets.canonical.DeliveryConfig.defaults()),
                root,
                Map.of("field_a", List.of("shared name"), "field_b", List.of("shared-name")),
                Path.of("test.yaml"));

        FieldAliasResolver.Result result = resolver.resolve(model, noConventions(), Set.of("Shared Name"));

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
