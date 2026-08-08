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
        assertThat(holdings.fieldAliases().get("currency")).containsExactly("Currency", "Ccy");
        assertThat(holdings.variantValues().get("asset_class"))
                .containsEntry("Fixed Income", "FixedIncome")
                .containsEntry("Equity", "Equity");
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
}
