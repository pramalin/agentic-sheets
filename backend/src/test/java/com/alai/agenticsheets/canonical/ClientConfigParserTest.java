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

    @Test
    void rejectsMissingDateFormat(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path bad = tmp.resolve("bad-client.yaml");
        Files.writeString(bad, "client: someclient\n");

        assertThatThrownBy(() -> parser.parse(bad))
                .isInstanceOf(CanonicalConfigException.class)
                .hasMessageContaining("dateFormat");
    }
}
