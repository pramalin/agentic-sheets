package com.alai.agenticsheets.canonical;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The property that actually matters here: one broken config file must
 * not prevent a good one from loading, and must not clear a model that
 * loaded successfully on a previous reload.
 */
class CanonicalModelRegistryTest {

    private static final String GOOD_MODEL = """
            model: GoodModel
            version: 1
            target:
              service: x
              transport: rest
              endpoint: https://example.com
              auth: { type: api-key, secretRef: X }
            types:
              Thing:
                kind: record
                fields:
                  name: String
            root: Thing
            """;

    private static final String GOOD_MODEL_V2 = GOOD_MODEL.replace("version: 1", "version: 2");

    @Test
    void isolatesAParseFailureFromAnUnrelatedGoodFile(@TempDir Path modelsDir, @TempDir Path clientsDir) throws Exception {
        Files.writeString(modelsDir.resolve("good.yaml"), GOOD_MODEL);
        Files.writeString(modelsDir.resolve("bad.yaml"), "not: [valid, structure: {");

        CanonicalModelRegistry registry = new CanonicalModelRegistry(
                modelsDir.toString(), clientsDir.toString(),
                new CanonicalModelParser(), new ClientConfigParser());
        registry.reload();

        assertThat(registry.get("GoodModel").version()).isEqualTo(1);
        assertThatThrownBy(() -> registry.get("BadModel"))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void aSubsequentBadEditKeepsThePreviousGoodVersionLive(@TempDir Path modelsDir, @TempDir Path clientsDir) throws Exception {
        Path modelFile = modelsDir.resolve("model.yaml");
        Files.writeString(modelFile, GOOD_MODEL);

        CanonicalModelRegistry registry = new CanonicalModelRegistry(
                modelsDir.toString(), clientsDir.toString(),
                new CanonicalModelParser(), new ClientConfigParser());
        registry.reload();
        assertThat(registry.get("GoodModel").version()).isEqualTo(1);

        // Simulate someone breaking the file mid-edit.
        Files.writeString(modelFile, "this is not valid yaml: [");
        registry.reload();

        // Still serving the last good version -- not cleared, not stale-erroring.
        assertThat(registry.get("GoodModel").version()).isEqualTo(1);
    }

    @Test
    void aSuccessfulEditReplacesThePreviousVersion(@TempDir Path modelsDir, @TempDir Path clientsDir) throws Exception {
        Path modelFile = modelsDir.resolve("model.yaml");
        Files.writeString(modelFile, GOOD_MODEL);

        CanonicalModelRegistry registry = new CanonicalModelRegistry(
                modelsDir.toString(), clientsDir.toString(),
                new CanonicalModelParser(), new ClientConfigParser());
        registry.reload();
        assertThat(registry.get("GoodModel").version()).isEqualTo(1);

        Files.writeString(modelFile, GOOD_MODEL_V2);
        registry.reload();

        assertThat(registry.get("GoodModel").version()).isEqualTo(2);
    }

    @Test
    void missingConfigDirectoryDoesNotThrow(@TempDir Path tmp) {
        Path doesNotExist = tmp.resolve("nope");
        CanonicalModelRegistry registry = new CanonicalModelRegistry(
                doesNotExist.toString(), doesNotExist.toString(),
                new CanonicalModelParser(), new ClientConfigParser());

        // Should log a warning and leave the registry empty, not throw --
        // this is exactly the state a plain `mvn test` run is in, since
        // there's no volume-mounted config directory outside a container.
        registry.reload();
        assertThat(registry.allModels()).isEmpty();
    }
}
