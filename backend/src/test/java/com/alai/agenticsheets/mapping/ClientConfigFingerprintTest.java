package com.alai.agenticsheets.mapping;

import com.alai.agenticsheets.canonical.ClientConfig;
import com.alai.agenticsheets.canonical.ClientModelConventions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ClientConfigFingerprint} is what makes mapping memory (Step 10)
 * correctly invalidate when a client's conventions change (Local LLM
 * phase, Step LLM-3 -- see {@code docs/local-llm-enhancements.md}). The
 * property that actually matters: two configs that differ in any
 * mapping-relevant way must hash differently, and two configs that
 * differ only in incidental map ordering must hash the same.
 */
class ClientConfigFingerprintTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final ClientConfigFingerprint fingerprint = new ClientConfigFingerprint(jsonMapper);

    private ClientConfig withConventions(Map<String, List<String>> fieldAliases,
            Map<String, Map<String, String>> variantValues) {
        return withConventions(fieldAliases, variantValues, List.of());
    }

    private ClientConfig withConventions(Map<String, List<String>> fieldAliases,
            Map<String, Map<String, String>> variantValues, List<String> notProvidedFields) {
        return new ClientConfig("jpmc", "yyyy-MM-dd", Map.of(),
                Map.of("Holdings", new ClientModelConventions(fieldAliases, variantValues, notProvidedFields)));
    }

    @Test
    void noConventions_stableAndDeterministic() {
        ClientConfig client = new ClientConfig("jpmc", "yyyy-MM-dd", Map.of(), Map.of());
        assertThat(fingerprint.hash(client)).isEqualTo(fingerprint.hash(client));
    }

    @Test
    void changingDateFormat_changesTheHash() {
        ClientConfig a = new ClientConfig("jpmc", "yyyy-MM-dd", Map.of(), Map.of());
        ClientConfig b = new ClientConfig("jpmc", "MM/dd/yyyy", Map.of(), Map.of());
        assertThat(fingerprint.hash(a)).isNotEqualTo(fingerprint.hash(b));
    }

    @Test
    void addingAFieldAlias_changesTheHash() {
        ClientConfig before = withConventions(Map.of(), Map.of());
        ClientConfig after = withConventions(Map.of("currency", List.of("Ccy")), Map.of());
        assertThat(fingerprint.hash(before)).isNotEqualTo(fingerprint.hash(after));
    }

    @Test
    void changingAVariantValueTarget_changesTheHash() {
        // This is the exact scenario the design conversation flagged as
        // the risk: a convention change (e.g. correcting a typo'd
        // variant target) must invalidate any mapping memory entry
        // approved under the old convention, not silently keep serving
        // a mapping that was approved under different semantics.
        ClientConfig before = withConventions(Map.of(), Map.of("currency", Map.of("USD", "USD")));
        ClientConfig after = withConventions(Map.of(), Map.of("currency", Map.of("USD", "EUR")));
        assertThat(fingerprint.hash(before)).isNotEqualTo(fingerprint.hash(after));
    }

    // notProvidedFields: post-benchmark hardening (see
    // docs/local-llm-enhancements.md's "twelfth real run" section). An
    // external review's own, critical catch: the original implementation
    // this section covers omitted this field from the hash entirely --
    // a real correctness gap, not a style nitpick. A mapping memory
    // entry approved while a field was declared not-provided must be
    // invalidated if that declaration later changes, the exact same
    // reasoning as changingAVariantValueTarget_changesTheHash above,
    // just for a different convention.

    @Test
    void addingANotProvidedField_changesTheHash() {
        ClientConfig before = withConventions(Map.of(), Map.of(), List.of());
        ClientConfig after = withConventions(Map.of(), Map.of(),
                List.of("asset_class.FixedIncome.maturity_date"));
        assertThat(fingerprint.hash(before)).isNotEqualTo(fingerprint.hash(after));
    }

    @Test
    void changingWhichFieldIsNotProvided_changesTheHash() {
        // Not just "adding one changes it" -- swapping which field is
        // declared absent must also change the hash, since that's a
        // genuinely different applicability rule, not an addition.
        ClientConfig a = withConventions(Map.of(), Map.of(),
                List.of("asset_class.FixedIncome.maturity_date"));
        ClientConfig b = withConventions(Map.of(), Map.of(),
                List.of("asset_class.FixedIncome.coupon_rate"));
        assertThat(fingerprint.hash(a)).isNotEqualTo(fingerprint.hash(b));
    }

    @Test
    void notProvidedFieldsOrderIndependence_hashesTheSame() {
        // Sorted explicitly before hashing (ClientConfigFingerprint's
        // own canonicalize() does this) -- two configs listing the same
        // set of not-provided fields in a different order must hash
        // identically, same reasoning as every other field here.
        ClientConfig a = withConventions(Map.of(), Map.of(), List.of(
                "asset_class.FixedIncome.maturity_date",
                "asset_class.FixedIncome.coupon_rate",
                "asset_class.FixedIncome.credit_rating"));
        ClientConfig b = withConventions(Map.of(), Map.of(), List.of(
                "asset_class.FixedIncome.credit_rating",
                "asset_class.FixedIncome.maturity_date",
                "asset_class.FixedIncome.coupon_rate"));
        assertThat(fingerprint.hash(a)).isEqualTo(fingerprint.hash(b));
    }

    @Test
    void sameContentDifferentMapInstanceOrdering_hashesTheSame() {
        // Map.of()/Map.copyOf() give no iteration-order guarantee, so
        // the hash must not depend on incidental map ordering -- two
        // configs that mean the same thing must always hash the same.
        Map<String, List<String>> aliasesA = Map.of(
                "currency", List.of("Ccy", "Curr"),
                "asset_class", List.of("Class"));
        Map<String, List<String>> aliasesB = Map.of(
                "asset_class", List.of("Class"),
                "currency", List.of("Curr", "Ccy")); // different key AND list order

        ClientConfig a = withConventions(aliasesA, Map.of());
        ClientConfig b = withConventions(aliasesB, Map.of());

        assertThat(fingerprint.hash(a)).isEqualTo(fingerprint.hash(b));
    }

    @Test
    void feedsAreExcludedFromTheHash() {
        // feeds is Step 9 routing metadata -- has no bearing on mapping
        // interpretation, so a routing-only change must not invalidate
        // mapping memory.
        ClientConfig a = new ClientConfig("jpmc", "yyyy-MM-dd", Map.of(), Map.of());
        ClientConfig b = new ClientConfig("jpmc", "yyyy-MM-dd",
                Map.of("holdings", new com.alai.agenticsheets.canonical.FeedRoute(
                        "holdings", "Holdings", List.of("Holdings"))),
                Map.of());
        assertThat(fingerprint.hash(a)).isEqualTo(fingerprint.hash(b));
    }

    // External review finding (post Step LLM-6): the original
    // delimiter-based serialization had a real, verified hash collision.
    // See docs/local-llm-enhancements.md.

    @Test
    void adversarialDelimiterCollisionInFieldAliases_stillHashesDifferently() {
        // The review's exact example: with the old "," -joined
        // serialization, alias lists ["a,b","c"] and ["a","b,c"], both
        // sorted, both concatenated to the identical string "a,b,c,".
        // Two semantically different configurations must never collide.
        ClientConfig a = withConventions(Map.of("currency", List.of("a,b", "c")), Map.of());
        ClientConfig b = withConventions(Map.of("currency", List.of("a", "b,c")), Map.of());

        assertThat(fingerprint.hash(a)).isNotEqualTo(fingerprint.hash(b));
    }

    @Test
    void adversarialDelimiterCollisionInVariantValues_stillHashesDifferently() {
        // Same class of bug, the other serialization path: with the old
        // "->" -joined serialization, a single entry {"A->B":"C"} and a
        // single entry {"A":"B->C"} both concatenate to "A->B->C," --
        // genuinely different key/value structures, same old hash input.
        ClientConfig a = withConventions(Map.of(), Map.of("currency", Map.of("A->B", "C")));
        ClientConfig b = withConventions(Map.of(), Map.of("currency", Map.of("A", "B->C")));

        assertThat(fingerprint.hash(a)).isNotEqualTo(fingerprint.hash(b));
    }
}
