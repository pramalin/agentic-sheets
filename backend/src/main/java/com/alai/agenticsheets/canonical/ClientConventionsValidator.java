package com.alai.agenticsheets.canonical;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Validates a client's per-model {@link ClientModelConventions} against
 * the actual canonical model they're declared for -- Local LLM phase,
 * Step LLM-3 (see {@code docs/local-llm-enhancements.md}). Checked at
 * config-load time (from {@link CanonicalModelRegistry#reloadClients},
 * the same place {@code feeds}' {@code modelId} references are already
 * validated), not deferred until a resolver actually tries to use a
 * bad entry at runtime.
 *
 * <p>Five things get checked:
 * <ul>
 *   <li>{@code fieldAliases} references a real field path in the
 *       referenced model;</li>
 *   <li>{@code variantValues} references a real sum-type field path, and
 *       every value it maps to is a real variant of that field;</li>
 *   <li>{@code notProvidedFields} (post-benchmark hardening -- see
 *       {@code docs/local-llm-enhancements.md}'s "twelfth real run"
 *       section) references a real field path in the referenced model,
 *       AND that field is genuinely optional in the schema -- excluding
 *       a required field from what the model is shown would make this
 *       client's feed permanently unsatisfiable, not just imprecise;</li>
 *   <li>a {@code notProvidedFields} path never also carries a
 *       {@code fieldAliases} or {@code variantValues} entry (an
 *       external review's own catch -- otherwise a config can legally
 *       declare a field both "this client's feed never provides it" and
 *       "here's how to recognize it when it's provided," a durable
 *       contradiction that would otherwise only surface as a confusing
 *       runtime rejection from {@link com.alai.agenticsheets.mapping.ClientConventionMappingValidator}
 *       the first time {@link com.alai.agenticsheets.mapping.FieldAliasResolver}
 *       actually resolved the field the alias was configured to
 *       recognize, rather than failing clearly at the point the
 *       contradiction was actually introduced);</li>
 *   <li>no two distinct alias strings (within one model's conventions,
 *       across different canonical fields) normalize to the same thing
 *       -- an ambiguous alias would make column-header matching
 *       (Step LLM-4) unable to tell which field a source column was
 *       actually meant for.</li>
 * </ul>
 *
 * <p>Uses {@link CanonicalFieldPaths} for the underlying "what are this
 * model's valid field paths" question -- originally implemented as a
 * private nested class here, extracted once a second consumer
 * ({@link CanonicalModelRegistry}'s synonym validation) needed the exact
 * same thing. See that class's own javadoc for why it's a second
 * implementation of {@code mapping.CanonicalPaths}' walk rather than a
 * shared dependency on it.
 */
final class ClientConventionsValidator {

    private ClientConventionsValidator() {
    }

    static void validate(ClientConfig client, Map<String, CanonicalModel> models) {
        for (Map.Entry<String, ClientModelConventions> entry : client.conventions().entrySet()) {
            String modelId = entry.getKey();
            ClientModelConventions conventions = entry.getValue();

            CanonicalModel model = models.get(modelId);
            if (model == null) {
                throw new CanonicalConfigException("client '" + client.clientId()
                        + "' has conventions for unknown canonical model '" + modelId + "'");
            }

            CanonicalFieldPaths paths = CanonicalFieldPaths.of(model.root());
            validateFieldAliases(client.clientId(), modelId, conventions.fieldAliases(), paths);
            validateVariantValues(client.clientId(), modelId, conventions.variantValues(), paths);
            validateNotProvidedFields(client.clientId(), modelId, conventions.notProvidedFields(), paths);
            validateNoContradictoryNotProvided(client.clientId(), modelId, conventions);
        }
    }

    private static void validateFieldAliases(String clientId, String modelId,
            Map<String, List<String>> fieldAliases, CanonicalFieldPaths paths) {
        // normalized alias -> the field path that first claimed it, so a
        // second, different field claiming the same normalized alias is
        // caught as ambiguous rather than silently overwriting/coexisting.
        Map<String, String> normalizedToOwningPath = new LinkedHashMap<>();

        for (Map.Entry<String, List<String>> entry : fieldAliases.entrySet()) {
            String path = entry.getKey();
            if (!paths.isValidPath(path)) {
                throw new CanonicalConfigException("client '" + clientId + "' model '" + modelId
                        + "' fieldAliases references '" + path + "', which is not a field in " + modelId);
            }
            for (String alias : entry.getValue()) {
                String normalized = normalize(alias);
                String owner = normalizedToOwningPath.putIfAbsent(normalized, path);
                if (owner != null && !owner.equals(path)) {
                    throw new CanonicalConfigException("client '" + clientId + "' model '" + modelId
                            + "' has ambiguous field aliases: '" + alias + "' (under '" + path
                            + "') normalizes the same as an alias already claimed by '" + owner + "'");
                }
            }
        }
    }

    private static void validateVariantValues(String clientId, String modelId,
            Map<String, Map<String, String>> variantValues, CanonicalFieldPaths paths) {
        for (Map.Entry<String, Map<String, String>> entry : variantValues.entrySet()) {
            String path = entry.getKey();
            Set<String> validVariants = paths.variantsAt(path);
            if (validVariants == null) {
                throw new CanonicalConfigException("client '" + clientId + "' model '" + modelId
                        + "' variantValues references '" + path + "', which is not a sum type field in " + modelId);
            }
            for (Map.Entry<String, String> mapping : entry.getValue().entrySet()) {
                String target = mapping.getValue();
                if (!validVariants.contains(target)) {
                    throw new CanonicalConfigException("client '" + clientId + "' model '" + modelId
                            + "' variantValues for '" + path + "' maps source value '" + mapping.getKey()
                            + "' to '" + target + "', which is not one of " + validVariants);
                }
            }
        }
    }

    private static void validateNotProvidedFields(String clientId, String modelId,
            List<String> notProvidedFields, CanonicalFieldPaths paths) {
        for (String path : notProvidedFields) {
            if (!paths.isValidPath(path)) {
                throw new CanonicalConfigException("client '" + clientId + "' model '" + modelId
                        + "' notProvidedFields references '" + path + "', which is not a field in " + modelId);
            }
            if (!paths.isOptionalPath(path)) {
                throw new CanonicalConfigException("client '" + clientId + "' model '" + modelId
                        + "' notProvidedFields references '" + path + "', which is a REQUIRED field in "
                        + modelId + " -- excluding it would make this client's feed permanently "
                        + "unsatisfiable; only genuinely optional fields may be declared not-provided");
            }
        }
    }

    /** An external review's own catch: {@code notProvidedFields} and
      * {@code fieldAliases}/{@code variantValues} are, by construction,
      * mutually exclusive claims about the same field -- "this client's
      * feed never provides it" versus "here's how to recognize it when
      * provided." A config declaring both for the same path is a
      * durable contradiction, not a runtime edge case: without this
      * check, {@link com.alai.agenticsheets.canonical.CanonicalModelRegistry}
      * would accept it, {@link com.alai.agenticsheets.mapping.FieldAliasResolver}
      * would deterministically resolve the field via the configured
      * alias whenever a matching column showed up, and
      * {@link com.alai.agenticsheets.mapping.ClientConventionMappingValidator}
      * would then reject the resulting proposal every single time --
      * the config itself is what's actually broken, and the person who
      * needs to know that is whoever edits the config, not whoever
      * happens to trigger a proposal against it later. */
    private static void validateNoContradictoryNotProvided(String clientId, String modelId,
            ClientModelConventions conventions) {
        for (String path : conventions.notProvidedFields()) {
            if (conventions.fieldAliases().containsKey(path)) {
                throw new CanonicalConfigException("client '" + clientId + "' model '" + modelId
                        + "' declares '" + path + "' in both notProvidedFields and fieldAliases -- "
                        + "contradictory: a field can't simultaneously be 'never provided by this client' "
                        + "and 'recognizable by this configured alias when provided'. Remove it from one.");
            }
            if (conventions.variantValues().containsKey(path)) {
                throw new CanonicalConfigException("client '" + clientId + "' model '" + modelId
                        + "' declares '" + path + "' in both notProvidedFields and variantValues -- "
                        + "contradictory: a field can't simultaneously be 'never provided by this client' "
                        + "and 'have known variant values when provided'. Remove it from one.");
            }
        }
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[\\s_-]", "");
    }
}
