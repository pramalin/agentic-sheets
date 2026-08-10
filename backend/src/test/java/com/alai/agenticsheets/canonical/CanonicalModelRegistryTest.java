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

    @Test
    void aFeedReferencingAnUnknownModelFailsThatClientAloneNotEverything(
            @TempDir Path modelsDir, @TempDir Path clientsDir) throws Exception {
        Files.writeString(modelsDir.resolve("good.yaml"), GOOD_MODEL);
        Files.writeString(clientsDir.resolve("broken.yaml"), """
                client: broken
                dateFormat: yyyy-MM-dd
                feeds:
                  someFeed:
                    modelId: NoSuchModel
                    worksheetNames:
                      - Sheet1
                """);
        Files.writeString(clientsDir.resolve("fine.yaml"), """
                client: fine
                dateFormat: yyyy-MM-dd
                feeds:
                  someFeed:
                    modelId: GoodModel
                    worksheetNames:
                      - Sheet1
                """);

        CanonicalModelRegistry registry = new CanonicalModelRegistry(
                modelsDir.toString(), clientsDir.toString(),
                new CanonicalModelParser(), new ClientConfigParser());
        registry.reload();

        assertThatThrownBy(() -> registry.getClient("broken"))
                .isInstanceOf(java.util.NoSuchElementException.class);
        assertThat(registry.getClient("fine").feeds()).containsKey("someFeed");
        assertThat(registry.resolveRoute("fine", "someFeed").modelId()).isEqualTo("GoodModel");
        assertThatThrownBy(() -> registry.resolveRoute("broken", "someFeed"))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    // --- Local LLM phase, Step LLM-4: synonym validation ---
    // See docs/local-llm-enhancements.md. synonyms keys were never
    // validated against real field paths at parse time -- harmless
    // while purely an LLM prompt hint, a real correctness risk now that
    // FieldAliasResolver makes them load-bearing for deterministic
    // resolution.

    @Test
    void synonymsReferencingAnUnknownFieldFailsThatModelAloneNotEverything(
            @TempDir Path modelsDir, @TempDir Path clientsDir) throws Exception {
        Files.writeString(modelsDir.resolve("good.yaml"), GOOD_MODEL);
        Files.writeString(modelsDir.resolve("broken.yaml"), """
                model: BrokenModel
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
                synonyms:
                  nonexistent_field: [some alias]
                """);

        CanonicalModelRegistry registry = new CanonicalModelRegistry(
                modelsDir.toString(), clientsDir.toString(),
                new CanonicalModelParser(), new ClientConfigParser());
        registry.reload();

        assertThatThrownBy(() -> registry.get("BrokenModel"))
                .isInstanceOf(java.util.NoSuchElementException.class);
        assertThat(registry.get("GoodModel")).isNotNull();
    }

    @Test
    void validSynonymsLoadSuccessfullyAndAreRetrievable(
            @TempDir Path modelsDir, @TempDir Path clientsDir) throws Exception {
        Files.writeString(modelsDir.resolve("model.yaml"), """
                model: WithSynonyms
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
                synonyms:
                  name: [full name, legal name]
                """);

        CanonicalModelRegistry registry = new CanonicalModelRegistry(
                modelsDir.toString(), clientsDir.toString(),
                new CanonicalModelParser(), new ClientConfigParser());
        registry.reload();

        assertThat(registry.get("WithSynonyms").synonyms().get("name"))
                .containsExactly("full name", "legal name");
    }

    // --- Local LLM phase, Step LLM-3: conventions validation ---
    // See docs/local-llm-enhancements.md. GOOD_MODEL has no sum type
    // field, so these tests use a small model that does, to exercise
    // variantValues validation as well as fieldAliases.

    private static final String MODEL_WITH_SUM_TYPE = """
            model: WithSumType
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
                  status: Status
              Status:
                kind: sum
                variants:
                  Active:
                    kind: record
                    fields: {}
                  Inactive:
                    kind: record
                    fields: {}
            root: Thing
            """;

    // notProvidedFields (post-benchmark hardening -- see
    // docs/local-llm-enhancements.md's "twelfth real run" section): an
    // external review's own point -- excluding a REQUIRED field from
    // what the model is ever shown would make a client's feed
    // permanently unsatisfiable, not just imprecise, so validation must
    // check optionality, not just that the path is real. Needs a model
    // with at least one genuinely optional field to test that
    // distinction; MODEL_WITH_SUM_TYPE's own two fields are both
    // required, so a separate small constant covers this instead of
    // extending that one's existing role in every other test above.
    private static final String MODEL_WITH_OPTIONAL_FIELD = """
            model: WithOptional
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
                  nickname: String?
            root: Thing
            """;

    // notProvidedFields (post-benchmark hardening -- see
    // docs/local-llm-enhancements.md's "review, client-config
    // governance" section): a genuinely optional SUM TYPE field is
    // needed to test that notProvidedFields/variantValues contradiction
    // specifically -- MODEL_WITH_SUM_TYPE's own status field is
    // required, so declaring it not-provided would already fail the
    // separate required-field check first, never reaching the
    // contradiction check this constant exists to test in isolation.
    private static final String MODEL_WITH_OPTIONAL_SUM_TYPE = """
            model: WithOptionalSumType
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
                  status: Status?
              Status:
                kind: sum
                variants:
                  Active:
                    kind: record
                    fields: {}
                  Inactive:
                    kind: record
                    fields: {}
            root: Thing
            """;

    @Test
    void validConventionsLoadSuccessfullyAndAreRetrievable(
            @TempDir Path modelsDir, @TempDir Path clientsDir) throws Exception {
        Files.writeString(modelsDir.resolve("model.yaml"), MODEL_WITH_SUM_TYPE);
        Files.writeString(clientsDir.resolve("acme.yaml"), """
                client: acme
                dateFormat: yyyy-MM-dd
                conventions:
                  WithSumType:
                    fieldAliases:
                      name: [Full Name, Legal Name]
                    variantValues:
                      status:
                        active: Active
                        Inactive: Inactive
                """);

        CanonicalModelRegistry registry = new CanonicalModelRegistry(
                modelsDir.toString(), clientsDir.toString(),
                new CanonicalModelParser(), new ClientConfigParser());
        registry.reload();

        ClientModelConventions conventions = registry.getClient("acme").conventions().get("WithSumType");
        assertThat(conventions.fieldAliases().get("name")).containsExactly("Full Name", "Legal Name");
        assertThat(conventions.variantValues().get("status")).containsEntry("active", "Active");
    }

    @Test
    void conventionsForUnknownModelFailsThatClientAloneNotEverything(
            @TempDir Path modelsDir, @TempDir Path clientsDir) throws Exception {
        Files.writeString(modelsDir.resolve("good.yaml"), GOOD_MODEL);
        Files.writeString(clientsDir.resolve("broken.yaml"), """
                client: broken
                dateFormat: yyyy-MM-dd
                conventions:
                  NoSuchModel:
                    fieldAliases:
                      name: [Alias]
                """);
        Files.writeString(clientsDir.resolve("fine.yaml"), """
                client: fine
                dateFormat: yyyy-MM-dd
                """);

        CanonicalModelRegistry registry = new CanonicalModelRegistry(
                modelsDir.toString(), clientsDir.toString(),
                new CanonicalModelParser(), new ClientConfigParser());
        registry.reload();

        assertThatThrownBy(() -> registry.getClient("broken"))
                .isInstanceOf(java.util.NoSuchElementException.class);
        assertThat(registry.getClient("fine")).isNotNull();
    }

    @Test
    void fieldAliasesReferencingUnknownFieldPathFails(
            @TempDir Path modelsDir, @TempDir Path clientsDir) throws Exception {
        Files.writeString(modelsDir.resolve("model.yaml"), MODEL_WITH_SUM_TYPE);
        Files.writeString(clientsDir.resolve("broken.yaml"), """
                client: broken
                dateFormat: yyyy-MM-dd
                conventions:
                  WithSumType:
                    fieldAliases:
                      nonexistent_field: [Alias]
                """);

        CanonicalModelRegistry registry = new CanonicalModelRegistry(
                modelsDir.toString(), clientsDir.toString(),
                new CanonicalModelParser(), new ClientConfigParser());
        registry.reload();

        assertThatThrownBy(() -> registry.getClient("broken"))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void variantValuesReferencingNonSumTypeFieldFails(
            @TempDir Path modelsDir, @TempDir Path clientsDir) throws Exception {
        Files.writeString(modelsDir.resolve("model.yaml"), MODEL_WITH_SUM_TYPE);
        Files.writeString(clientsDir.resolve("broken.yaml"), """
                client: broken
                dateFormat: yyyy-MM-dd
                conventions:
                  WithSumType:
                    variantValues:
                      name:
                        someValue: SomeVariant
                """);

        CanonicalModelRegistry registry = new CanonicalModelRegistry(
                modelsDir.toString(), clientsDir.toString(),
                new CanonicalModelParser(), new ClientConfigParser());
        registry.reload();

        assertThatThrownBy(() -> registry.getClient("broken"))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void notProvidedFieldsReferencingARequiredFieldFails(
            @TempDir Path modelsDir, @TempDir Path clientsDir) throws Exception {
        // The critical distinction an external review caught: "name" is
        // a REAL field in WithOptional, so a check that only asked "is
        // this a valid path" would wrongly accept it -- excluding a
        // required field would make this client's feed permanently
        // unsatisfiable, not just imprecise.
        Files.writeString(modelsDir.resolve("model.yaml"), MODEL_WITH_OPTIONAL_FIELD);
        Files.writeString(clientsDir.resolve("broken.yaml"), """
                client: broken
                dateFormat: yyyy-MM-dd
                conventions:
                  WithOptional:
                    notProvidedFields:
                      - name
                """);

        CanonicalModelRegistry registry = new CanonicalModelRegistry(
                modelsDir.toString(), clientsDir.toString(),
                new CanonicalModelParser(), new ClientConfigParser());
        registry.reload();

        assertThatThrownBy(() -> registry.getClient("broken"))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void notProvidedFieldsReferencingAGenuinelyOptionalFieldSucceeds(
            @TempDir Path modelsDir, @TempDir Path clientsDir) throws Exception {
        Files.writeString(modelsDir.resolve("model.yaml"), MODEL_WITH_OPTIONAL_FIELD);
        Files.writeString(clientsDir.resolve("good.yaml"), """
                client: good
                dateFormat: yyyy-MM-dd
                conventions:
                  WithOptional:
                    notProvidedFields:
                      - nickname
                """);

        CanonicalModelRegistry registry = new CanonicalModelRegistry(
                modelsDir.toString(), clientsDir.toString(),
                new CanonicalModelParser(), new ClientConfigParser());
        registry.reload();

        ClientConfig client = registry.getClient("good");
        assertThat(client.conventions().get("WithOptional").notProvidedFields()).containsExactly("nickname");
    }

    @Test
    void notProvidedFieldsReferencingAnUnknownPathFails(
            @TempDir Path modelsDir, @TempDir Path clientsDir) throws Exception {
        Files.writeString(modelsDir.resolve("model.yaml"), MODEL_WITH_OPTIONAL_FIELD);
        Files.writeString(clientsDir.resolve("broken.yaml"), """
                client: broken
                dateFormat: yyyy-MM-dd
                conventions:
                  WithOptional:
                    notProvidedFields:
                      - nonexistent_field
                """);

        CanonicalModelRegistry registry = new CanonicalModelRegistry(
                modelsDir.toString(), clientsDir.toString(),
                new CanonicalModelParser(), new ClientConfigParser());
        registry.reload();

        assertThatThrownBy(() -> registry.getClient("broken"))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    // Post-benchmark hardening (see docs/local-llm-enhancements.md's
    // "review, client-config governance" section): an external review's
    // own catch -- notProvidedFields and fieldAliases/variantValues are
    // mutually exclusive claims about the same field. A config
    // declaring both is a durable contradiction, not a runtime edge
    // case: it would otherwise be accepted at load time, resolved
    // deterministically by FieldAliasResolver whenever a matching
    // column showed up, and rejected by ClientConventionMappingValidator
    // every single time after that -- a confusing runtime failure for a
    // problem that was actually in the config itself.

    @Test
    void notProvidedFieldsContradictingFieldAliasesFailsAtLoadTime(
            @TempDir Path modelsDir, @TempDir Path clientsDir) throws Exception {
        Files.writeString(modelsDir.resolve("model.yaml"), MODEL_WITH_OPTIONAL_FIELD);
        Files.writeString(clientsDir.resolve("broken.yaml"), """
                client: broken
                dateFormat: yyyy-MM-dd
                conventions:
                  WithOptional:
                    fieldAliases:
                      nickname: [Nick]
                    notProvidedFields:
                      - nickname
                """);

        CanonicalModelRegistry registry = new CanonicalModelRegistry(
                modelsDir.toString(), clientsDir.toString(),
                new CanonicalModelParser(), new ClientConfigParser());
        registry.reload();

        assertThatThrownBy(() -> registry.getClient("broken"))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void notProvidedFieldsContradictingVariantValuesFailsAtLoadTime(
            @TempDir Path modelsDir, @TempDir Path clientsDir) throws Exception {
        Files.writeString(modelsDir.resolve("model.yaml"), MODEL_WITH_OPTIONAL_SUM_TYPE);
        Files.writeString(clientsDir.resolve("broken.yaml"), """
                client: broken
                dateFormat: yyyy-MM-dd
                conventions:
                  WithOptionalSumType:
                    variantValues:
                      status:
                        active: Active
                    notProvidedFields:
                      - status
                """);

        CanonicalModelRegistry registry = new CanonicalModelRegistry(
                modelsDir.toString(), clientsDir.toString(),
                new CanonicalModelParser(), new ClientConfigParser());
        registry.reload();

        assertThatThrownBy(() -> registry.getClient("broken"))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void variantValuesMappingToAnInvalidVariantNameFails(
            @TempDir Path modelsDir, @TempDir Path clientsDir) throws Exception {
        Files.writeString(modelsDir.resolve("model.yaml"), MODEL_WITH_SUM_TYPE);
        Files.writeString(clientsDir.resolve("broken.yaml"), """
                client: broken
                dateFormat: yyyy-MM-dd
                conventions:
                  WithSumType:
                    variantValues:
                      status:
                        pending: Pending
                """);

        CanonicalModelRegistry registry = new CanonicalModelRegistry(
                modelsDir.toString(), clientsDir.toString(),
                new CanonicalModelParser(), new ClientConfigParser());
        registry.reload();

        assertThatThrownBy(() -> registry.getClient("broken"))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void ambiguousFieldAliasesAcrossDifferentFieldsFails(
            @TempDir Path modelsDir, @TempDir Path clientsDir) throws Exception {
        // "Full-Name" (under name) and "full_name" (a hypothetical second
        // field, here re-using "status" just to have two distinct paths)
        // both normalize to "fullname" -- ambiguous, since a source
        // column header couldn't be resolved to just one of them.
        Files.writeString(modelsDir.resolve("model.yaml"), MODEL_WITH_SUM_TYPE);
        Files.writeString(clientsDir.resolve("broken.yaml"), """
                client: broken
                dateFormat: yyyy-MM-dd
                conventions:
                  WithSumType:
                    fieldAliases:
                      name: ["Full-Name"]
                      status: ["full_name"]
                """);

        CanonicalModelRegistry registry = new CanonicalModelRegistry(
                modelsDir.toString(), clientsDir.toString(),
                new CanonicalModelParser(), new ClientConfigParser());
        registry.reload();

        assertThatThrownBy(() -> registry.getClient("broken"))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void sameAliasRepeatedUnderTheSameFieldIsNotAmbiguous(
            @TempDir Path modelsDir, @TempDir Path clientsDir) throws Exception {
        // Two aliases that normalize the same, but both legitimately
        // belong to the SAME field, are fine -- only a collision across
        // two DIFFERENT fields is an error.
        Files.writeString(modelsDir.resolve("model.yaml"), MODEL_WITH_SUM_TYPE);
        Files.writeString(clientsDir.resolve("fine.yaml"), """
                client: fine
                dateFormat: yyyy-MM-dd
                conventions:
                  WithSumType:
                    fieldAliases:
                      name: ["Full Name", "full-name"]
                """);

        CanonicalModelRegistry registry = new CanonicalModelRegistry(
                modelsDir.toString(), clientsDir.toString(),
                new CanonicalModelParser(), new ClientConfigParser());
        registry.reload();

        assertThat(registry.getClient("fine").conventions().get("WithSumType").fieldAliases().get("name"))
                .containsExactly("Full Name", "full-name");
    }
}
