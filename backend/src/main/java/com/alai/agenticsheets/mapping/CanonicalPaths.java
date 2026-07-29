package com.alai.agenticsheets.mapping;

import com.alai.agenticsheets.canonical.CanonicalModel;
import com.alai.agenticsheets.canonical.CanonicalType;
import com.alai.agenticsheets.canonical.OptionType;
import com.alai.agenticsheets.canonical.PrimitiveType;
import com.alai.agenticsheets.canonical.RecordType;
import com.alai.agenticsheets.canonical.SumType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Flattens a canonical model's ADT into two lookup indices:
 * {@link #allPaths()} (every valid {@code canonicalFieldPath} a proposal
 * could reference) and {@link #variantsAt(String)} (for a sum type
 * field's path, the variant names actually valid there). Used by
 * {@link MappingProposalStructuralValidator} to check the agent's
 * generic output against the specific ADT it was shown.
 *
 * Deliberately separate from {@link CanonicalModelPromptRenderer}: that
 * class renders for a human/LLM to read, this one indexes for
 * validation. Conflating the two would make either change riskier to
 * make safely on its own.
 */
public final class CanonicalPaths {

    private final Set<String> allPaths = new LinkedHashSet<>();
    private final Map<String, Set<String>> variantsByPath = new LinkedHashMap<>();
    private final Map<String, PrimitiveType.Kind> primitiveKindByPath = new LinkedHashMap<>();

    private CanonicalPaths() {
    }

    public static CanonicalPaths of(CanonicalModel model) {
        CanonicalPaths result = new CanonicalPaths();
        result.walk("", model.root());
        return result;
    }

    public Set<String> allPaths() {
        return Collections.unmodifiableSet(allPaths);
    }

    public boolean isValidPath(String path) {
        return allPaths.contains(path);
    }

    public boolean isSumTypePath(String path) {
        return variantsByPath.containsKey(path);
    }

    public Set<String> variantsAt(String path) {
        return variantsByPath.getOrDefault(path, Set.of());
    }

    /** @return the primitive kind at this path, or null if the path
      * isn't a primitive field (e.g. it's a sum type field itself). */
    public PrimitiveType.Kind primitiveKindAt(String path) {
        return primitiveKindByPath.get(path);
    }

    private void walk(String path, CanonicalType type) {
        switch (type) {
            case OptionType o -> walk(path, o.inner());

            case PrimitiveType p -> {
                allPaths.add(path);
                primitiveKindByPath.put(path, p.kind());
            }

            case SumType s -> {
                allPaths.add(path);
                variantsByPath.put(path, new LinkedHashSet<>(s.variants().keySet()));
                for (Map.Entry<String, RecordType> entry : s.variants().entrySet()) {
                    String variantPath = path + "." + entry.getKey();
                    for (Map.Entry<String, CanonicalType> fieldEntry : entry.getValue().fields().entrySet()) {
                        walk(variantPath + "." + fieldEntry.getKey(), fieldEntry.getValue());
                    }
                }
            }

            case RecordType r -> {
                for (Map.Entry<String, CanonicalType> entry : r.fields().entrySet()) {
                    String fieldPath = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
                    walk(fieldPath, entry.getValue());
                }
            }
        }
    }
}
