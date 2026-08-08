package com.alai.agenticsheets.canonical;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * <p>Three things get checked:
 * <ul>
 *   <li>{@code fieldAliases} references a real field path in the
 *       referenced model;</li>
 *   <li>{@code variantValues} references a real sum-type field path, and
 *       every value it maps to is a real variant of that field;</li>
 *   <li>no two distinct alias strings (within one model's conventions,
 *       across different canonical fields) normalize to the same thing
 *       -- an ambiguous alias would make column-header matching
 *       (Step LLM-4) unable to tell which field a source column was
 *       actually meant for.</li>
 * </ul>
 *
 * <p>Deliberately reimplements a small, self-contained ADT walk (just
 * enough to know a model's valid field paths and, for sum-type paths,
 * their valid variant names) rather than depending on
 * {@code mapping.CanonicalPaths}, which already does this same job.
 * Nothing in the {@code canonical} package depends on {@code mapping}
 * anywhere else in this codebase -- {@code mapping} depends on
 * {@code canonical}, never the reverse -- and this validator runs from
 * {@link CanonicalModelRegistry}, itself in {@code canonical}.
 * Introducing the reverse dependency just for one call site would invert
 * an established architectural boundary; the small amount of duplicated
 * traversal logic here is the more contained cost.
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

            PathIndex index = PathIndex.of(model.root());
            validateFieldAliases(client.clientId(), modelId, conventions.fieldAliases(), index);
            validateVariantValues(client.clientId(), modelId, conventions.variantValues(), index);
        }
    }

    private static void validateFieldAliases(String clientId, String modelId,
            Map<String, List<String>> fieldAliases, PathIndex index) {
        // normalized alias -> the field path that first claimed it, so a
        // second, different field claiming the same normalized alias is
        // caught as ambiguous rather than silently overwriting/coexisting.
        Map<String, String> normalizedToOwningPath = new LinkedHashMap<>();

        for (Map.Entry<String, List<String>> entry : fieldAliases.entrySet()) {
            String path = entry.getKey();
            if (!index.paths().contains(path)) {
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
            Map<String, Map<String, String>> variantValues, PathIndex index) {
        for (Map.Entry<String, Map<String, String>> entry : variantValues.entrySet()) {
            String path = entry.getKey();
            Set<String> validVariants = index.variantsAt(path);
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

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[\\s_-]", "");
    }

    /** Minimal ADT index: every valid field path, and for a sum-type
      * path, its valid variant names. See this class's own javadoc for
      * why this doesn't reuse {@code mapping.CanonicalPaths}. */
    private record PathIndex(Set<String> paths, Map<String, Set<String>> variantsByPath) {

        static PathIndex of(CanonicalType root) {
            Set<String> paths = new LinkedHashSet<>();
            Map<String, Set<String>> variantsByPath = new LinkedHashMap<>();
            walk("", root, paths, variantsByPath);
            return new PathIndex(paths, variantsByPath);
        }

        Set<String> variantsAt(String path) {
            return variantsByPath.get(path);
        }

        private static void walk(String path, CanonicalType type, Set<String> paths,
                Map<String, Set<String>> variantsByPath) {
            switch (type) {
                case OptionType o -> walk(path, o.inner(), paths, variantsByPath);
                case PrimitiveType p -> paths.add(path);
                case SumType s -> {
                    paths.add(path);
                    variantsByPath.put(path, new LinkedHashSet<>(s.variants().keySet()));
                    for (Map.Entry<String, RecordType> variant : s.variants().entrySet()) {
                        String variantPath = path + "." + variant.getKey();
                        for (Map.Entry<String, CanonicalType> field : variant.getValue().fields().entrySet()) {
                            walk(variantPath + "." + field.getKey(), field.getValue(), paths, variantsByPath);
                        }
                    }
                }
                case RecordType r -> {
                    for (Map.Entry<String, CanonicalType> field : r.fields().entrySet()) {
                        String fieldPath = path.isEmpty() ? field.getKey() : path + "." + field.getKey();
                        walk(fieldPath, field.getValue(), paths, variantsByPath);
                    }
                }
            }
        }
    }
}
