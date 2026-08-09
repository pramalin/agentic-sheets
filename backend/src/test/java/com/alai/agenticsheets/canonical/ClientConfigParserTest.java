package com.alai.agenticsheets.canonical;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientConfigParserTest {

    private final ClientConfigParser parser = new ClientConfigParser();

    private Path resource(String name) throws Exception {
        return Path.of(getClass().getClassLoader().getResource(name).toURI());
    }

    @Test
    void parsesPimco() throws Exception {
        ClientConfig config = parser.parse(resource("client-configs/pimco.yaml"));
        assertThat(config.clientId()).isEqualTo("pimco");
        assertThat(config.dateFormat()).isEqualTo("MM/dd/yyyy");
    }

    @Test
    void parsesJpmc() throws Exception {
        ClientConfig config = parser.parse(resource("client-configs/jpmc.yaml"));
        assertThat(config.clientId()).isEqualTo("jpmc");
    }

    // --- Local LLM phase, Step LLM-3: conventions parsing ---
    // See docs/local-llm-enhancements.md. Purely structural here --
    // right shapes, non-blank keys/values -- not validated against any
    // canonical model (that's CanonicalModelRegistry's job, mirroring
    // how feeds' modelId references are handled).

    @Test
    void parsesJpmcConventions() throws Exception {
        ClientConfig config = parser.parse(resource("client-configs/jpmc.yaml"));

        ClientModelConventions holdings = config.conventions().get("Holdings");
        assertThat(holdings).isNotNull();
        // Post-benchmark hardening (see docs/local-llm-enhancements.md's
        // "twelfth real run" section): an external review's own
        // suggestion -- promoted from own-name-only resolution into
        // configured aliases, since they're already this project's own
        // documented, approved JPMC mapping (mapping-notes.md).
        assertThat(holdings.fieldAliases().get("account_id")).containsExactly("Account");
        assertThat(holdings.fieldAliases().get("security_id")).containsExactly("CUSIP");
        assertThat(holdings.fieldAliases().get("security_description")).containsExactly("Description");
        assertThat(holdings.fieldAliases().get("market_price")).containsExactly("Price");
        assertThat(holdings.fieldAliases().get("currency")).containsExactly("Currency", "Ccy");
        assertThat(holdings.variantValues().get("asset_class"))
                .containsEntry("Fixed Income", "FixedIncome")
                .containsEntry("Equity", "Equity");
        assertThat(holdings.notProvidedFields()).containsExactlyInAnyOrder(
                "asset_class.FixedIncome.maturity_date",
                "asset_class.FixedIncome.coupon_rate",
                "asset_class.FixedIncome.credit_rating");
    }

    @Test
    void absentConventionsBlockParsesToEmptyMap() throws Exception {
        ClientConfig config = parser.parse(resource("client-configs/pimco.yaml"));
        assertThat(config.conventions()).isEmpty();
    }

    @Test
    void rejectsConventionsThatIsNotAMap(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path bad = tmp.resolve("bad-client.yaml");
        Files.writeString(bad, """
                client: someclient
                dateFormat: yyyy-MM-dd
                conventions: [not, a, map]
                """);

        assertThatThrownBy(() -> parser.parse(bad))
                .isInstanceOf(CanonicalConfigException.class)
                .hasMessageContaining("conventions");
    }

    @Test
    void rejectsFieldAliasesEntryThatIsNotAList(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path bad = tmp.resolve("bad-client.yaml");
        Files.writeString(bad, """
                client: someclient
                dateFormat: yyyy-MM-dd
                conventions:
                  SomeModel:
                    fieldAliases:
                      currency: "not a list"
                """);

        assertThatThrownBy(() -> parser.parse(bad))
                .isInstanceOf(CanonicalConfigException.class)
                .hasMessageContaining("fieldAliases");
    }

    @Test
    void rejectsBlankFieldAliasEntry(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path bad = tmp.resolve("bad-client.yaml");
        Files.writeString(bad, """
                client: someclient
                dateFormat: yyyy-MM-dd
                conventions:
                  SomeModel:
                    fieldAliases:
                      currency: ["Ccy", ""]
                """);

        assertThatThrownBy(() -> parser.parse(bad))
                .isInstanceOf(CanonicalConfigException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void rejectsEmptyVariantValuesMap(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path bad = tmp.resolve("bad-client.yaml");
        Files.writeString(bad, """
                client: someclient
                dateFormat: yyyy-MM-dd
                conventions:
                  SomeModel:
                    variantValues:
                      currency: {}
                """);

        assertThatThrownBy(() -> parser.parse(bad))
                .isInstanceOf(CanonicalConfigException.class)
                .hasMessageContaining("must not be empty");
    }

    @Test
    void rejectsMissingDateFormat(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path bad = tmp.resolve("bad-client.yaml");
        Files.writeString(bad, "client: someclient\n");

        assertThatThrownBy(() -> parser.parse(bad))
                .isInstanceOf(CanonicalConfigException.class)
                .hasMessageContaining("dateFormat");
    }

    // notProvidedFields (post-benchmark hardening -- see
    // docs/local-llm-enhancements.md's "twelfth real run" section).
    // Purely structural here, same reasoning as fieldAliases/variantValues
    // above -- semantic validation (real path, genuinely optional) is
    // CanonicalModelRegistry's job via ClientConventionsValidator.

    @Test
    void absentNotProvidedFieldsParsesToEmptyList(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path config = tmp.resolve("client.yaml");
        Files.writeString(config, """
                client: someclient
                dateFormat: yyyy-MM-dd
                conventions:
                  SomeModel:
                    fieldAliases:
                      currency: [Ccy]
                """);

        ClientModelConventions conventions = parser.parse(config).conventions().get("SomeModel");
        assertThat(conventions.fieldAliases()).containsKey("currency");
        assertThat(conventions.notProvidedFields()).isEmpty();
    }

    @Test
    void rejectsNotProvidedFieldsThatIsNotAList(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path bad = tmp.resolve("bad-client.yaml");
        Files.writeString(bad, """
                client: someclient
                dateFormat: yyyy-MM-dd
                conventions:
                  SomeModel:
                    notProvidedFields: "not a list"
                """);

        assertThatThrownBy(() -> parser.parse(bad))
                .isInstanceOf(CanonicalConfigException.class)
                .hasMessageContaining("notProvidedFields");
    }

    @Test
    void rejectsEmptyNotProvidedFieldsList(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path bad = tmp.resolve("bad-client.yaml");
        Files.writeString(bad, """
                client: someclient
                dateFormat: yyyy-MM-dd
                conventions:
                  SomeModel:
                    notProvidedFields: []
                """);

        assertThatThrownBy(() -> parser.parse(bad))
                .isInstanceOf(CanonicalConfigException.class)
                .hasMessageContaining("non-empty");
    }

    @Test
    void rejectsBlankNotProvidedFieldsEntry(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path bad = tmp.resolve("bad-client.yaml");
        Files.writeString(bad, """
                client: someclient
                dateFormat: yyyy-MM-dd
                conventions:
                  SomeModel:
                    notProvidedFields: ["some.path", ""]
                """);

        assertThatThrownBy(() -> parser.parse(bad))
                .isInstanceOf(CanonicalConfigException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void rejectsDuplicateNotProvidedFieldsEntry(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        // An external review's own point: silently tolerating a
        // repeated path is a real, if minor, sign of a copy-paste
        // mistake worth catching -- unlike a duplicate list entry
        // elsewhere in this parser, this one IS rejected.
        Path bad = tmp.resolve("bad-client.yaml");
        Files.writeString(bad, """
                client: someclient
                dateFormat: yyyy-MM-dd
                conventions:
                  SomeModel:
                    notProvidedFields: ["some.path", "some.path"]
                """);

        assertThatThrownBy(() -> parser.parse(bad))
                .isInstanceOf(CanonicalConfigException.class)
                .hasMessageContaining("duplicate");
    }
}
