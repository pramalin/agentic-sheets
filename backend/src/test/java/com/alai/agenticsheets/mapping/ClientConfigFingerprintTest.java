package com.alai.agenticsheets.mapping;

import com.alai.agenticsheets.canonical.ClientConfig;
import com.alai.agenticsheets.canonical.ClientModelConventions;
import org.junit.jupiter.api.Test;

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

    private final ClientConfigFingerprint fingerprint = new ClientConfigFingerprint();

    private ClientConfig withConventions(Map<String, List<String>> fieldAliases,
            Map<String, Map<String, String>> variantValues) {
        return new ClientConfig("jpmc", "yyyy-MM-dd", Map.of(),
                Map.of("Holdings", new ClientModelConventions(fieldAliases, variantValues)));
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
}
