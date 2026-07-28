package com.alai.agenticsheets.canonical;

import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises CanonicalModelParser directly against the real project
 * configs (copied into test resources) plus a handful of deliberately
 * malformed inline fixtures for the validation paths.
 */
class CanonicalModelParserTest {

    private final CanonicalModelParser parser = new CanonicalModelParser();

    private Path resource(String name) throws URISyntaxException {
        return Path.of(getClass().getClassLoader().getResource(name).toURI());
    }

    @Test
    void parsesHoldingsWithSumTypeAssetClass() throws Exception {
        CanonicalModel model = parser.parse(resource("canonical-models/holdings.yaml"));

        assertThat(model.modelId()).isEqualTo("Holdings");
        assertThat(model.version()).isEqualTo(1);
        assertThat(model.target().service()).isEqualTo("holdings-writer");
        assertThat(model.target().transport()).isEqualTo("rest");
        assertThat(model.target().delivery().maxAttempts()).isEqualTo(8); // explicit override in the file

        assertThat(model.root()).isInstanceOf(RecordType.class);
        RecordType holding = (RecordType) model.root();

        assertThat(holding.fields().get("as_of_date"))
                .isInstanceOf(PrimitiveType.class);
        assertThat(((PrimitiveType) holding.fields().get("as_of_date")).format())
                .isEqualTo("yyyy-MM-dd");

        assertThat(holding.fields().get("asset_class")).isInstanceOf(SumType.class);
        SumType assetClass = (SumType) holding.fields().get("asset_class");
        assertThat(assetClass.variants()).containsKeys("Equity", "FixedIncome", "Cash", "Alternative");
        assertThat(assetClass.variants().get("Equity").fields()).isEmpty();
        assertThat(assetClass.variants().get("FixedIncome").fields())
                .containsKeys("maturity_date", "coupon_rate", "credit_rating");

        assertThat(holding.fields().get("custodian")).isInstanceOf(OptionType.class);
    }

    @Test
    void parsesMarketRateBookValueAsProductType() throws Exception {
        CanonicalModel model = parser.parse(resource("canonical-models/market_rate_book_value.yaml"));

        assertThat(model.target().transport()).isEqualTo("mcp");
        assertThat(model.target().tool()).isEqualTo("upsert_rate_and_book_value");
        // No delivery: block in this file -- should fall back to defaults.
        assertThat(model.target().delivery().maxAttempts()).isEqualTo(DeliveryConfig.defaults().maxAttempts());

        RecordType entry = (RecordType) model.root();
        assertThat(entry.fields()).containsKeys("market_rate", "book_value");
        assertThat(entry.fields().get("market_rate")).isInstanceOf(PrimitiveType.class);
        assertThat(entry.fields().get("book_value")).isInstanceOf(PrimitiveType.class);
    }

    @Test
    void rejectsUndefinedTypeReference(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path bad = tmp.resolve("bad.yaml");
        Files.writeString(bad, minimalModel("""
                types:
                  Thing:
                    kind: record
                    fields:
                      x: DoesNotExist
                root: Thing
                """));

        assertThatThrownBy(() -> parser.parse(bad))
                .isInstanceOf(CanonicalConfigException.class)
                .hasMessageContaining("DoesNotExist");
    }

    @Test
    void rejectsCyclicTypes(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path bad = tmp.resolve("cyclic.yaml");
        Files.writeString(bad, minimalModel("""
                types:
                  A:
                    kind: record
                    fields:
                      b: B
                  B:
                    kind: record
                    fields:
                      a: A
                root: A
                """));

        assertThatThrownBy(() -> parser.parse(bad))
                .isInstanceOf(CanonicalConfigException.class)
                .hasMessageContaining("cyclic");
    }

    @Test
    void rejectsSumWithNoVariants(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path bad = tmp.resolve("empty-sum.yaml");
        Files.writeString(bad, minimalModel("""
                types:
                  Empty:
                    kind: sum
                    variants: {}
                  Thing:
                    kind: record
                    fields:
                      e: Empty
                root: Thing
                """));

        assertThatThrownBy(() -> parser.parse(bad))
                .isInstanceOf(CanonicalConfigException.class)
                .hasMessageContaining("no variants");
    }

    @Test
    void rejectsMalformedYamlSyntax(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path bad = tmp.resolve("syntax-error.yaml");
        Files.writeString(bad, "not: [valid, structure: {");

        assertThatThrownBy(() -> parser.parse(bad))
                .isInstanceOf(CanonicalConfigException.class);
    }

    /** Wraps a `types:`/`root:` fragment with the minimum required
      * top-level keys (model, version, target) so each validation test
      * only has to specify the part it's actually exercising. */
    private String minimalModel(String typesAndRoot) {
        return """
                model: TestModel
                version: 1
                target:
                  service: test-service
                  transport: rest
                  endpoint: https://example.com
                  auth:
                    type: api-key
                    secretRef: TEST_KEY
                """ + typesAndRoot;
    }
}
