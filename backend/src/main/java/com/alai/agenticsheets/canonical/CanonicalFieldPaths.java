package com.alai.agenticsheets.canonical;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * A minimal ADT index -- every valid canonical field path, and for a
 * sum-type path, its valid variant names. Extracted from
 * {@link ClientConventionsValidator}'s original private {@code PathIndex}
 * (Step LLM-3) when a second consumer needed the exact same thing --
 * Local LLM phase, Step LLM-4's field-alias work (see
 * {@code docs/local-llm-enhancements.md}) makes {@link CanonicalModel#synonyms()}
 * load-bearing for deterministic resolution for the first time, and a
 * synonym entry referencing a field that doesn't exist needed the same
 * "validate at load time, not when a resolver stumbles on it later"
 * treatment {@link ClientConventionsValidator} already gives client
 * conventions.
 *
 * <p>Package-visible, not public -- consumed by {@link ClientConventionsValidator}
 * and {@link CanonicalModelRegistry}, both in this package. Deliberately
 * a second implementation of the same walk {@code mapping.CanonicalPaths}
 * already does, not a shared dependency on it: nothing in the
 * {@code canonical} package depends on {@code mapping} anywhere else in
 * this codebase ({@code mapping} depends on {@code canonical}, never the
 * reverse), and both of this class's callers run from {@code canonical}
 * package classes. See {@link ClientConventionsValidator}'s own original
 * javadoc (preserved there) for the fuller reasoning on why that
 * boundary is worth the small duplication rather than inverting it.
 */
final class CanonicalFieldPaths {

    private final Set<String> paths = new LinkedHashSet<>();
    private final Map<String, Set<String>> variantsByPath = new LinkedHashMap<>();

    private CanonicalFieldPaths() {
    }

    static CanonicalFieldPaths of(CanonicalType root) {
        CanonicalFieldPaths result = new CanonicalFieldPaths();
        result.walk("", root);
        return result;
    }

    Set<String> paths() {
        return Collections.unmodifiableSet(paths);
    }

    boolean isValidPath(String path) {
        return paths.contains(path);
    }

    /** @return the valid variant names at {@code path}, or {@code null}
      * if {@code path} isn't a sum-type field -- {@code null}, not
      * empty, so callers can distinguish "not a sum type" from "a sum
      * type with (impossibly) zero variants," matching the original
      * {@code PathIndex}'s own contract exactly. */
    Set<String> variantsAt(String path) {
        return variantsByPath.get(path);
    }

    private void walk(String path, CanonicalType type) {
        switch (type) {
            case OptionType o -> walk(path, o.inner());
            case PrimitiveType p -> paths.add(path);
            case SumType s -> {
                paths.add(path);
                variantsByPath.put(path, new LinkedHashSet<>(s.variants().keySet()));
                for (Map.Entry<String, RecordType> variant : s.variants().entrySet()) {
                    String variantPath = path + "." + variant.getKey();
                    for (Map.Entry<String, CanonicalType> field : variant.getValue().fields().entrySet()) {
                        walk(variantPath + "." + field.getKey(), field.getValue());
                    }
                }
            }
            case RecordType r -> {
                for (Map.Entry<String, CanonicalType> field : r.fields().entrySet()) {
                    String fieldPath = path.isEmpty() ? field.getKey() : path + "." + field.getKey();
                    walk(fieldPath, field.getValue());
                }
            }
        }
    }
}
