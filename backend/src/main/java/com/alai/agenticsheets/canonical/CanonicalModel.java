package com.alai.agenticsheets.canonical;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * One entry in {@link CanonicalModelRegistry} -- the parsed, validated
 * form of one team's config file. Despite the name, this carries no
 * business meaning about what "Holdings" or any other model actually
 * represents; it's the same generic shape (an id, a version, a target, a
 * root {@link CanonicalType}) regardless of which team's file it came
 * from. {@code agentic-sheets} itself has no domain knowledge of any
 * canonical model beyond what's in this record -- the canonical model is
 * known to the mapping agent (Step 6) only as whatever configuration a
 * team provided, nothing more.
 *
 * {@code version} is load-bearing: pinned on a {@code mapping_proposal}
 * when it's created, and part of the {@code mapping_memory} cache key,
 * so a later config change doesn't retroactively affect work already in
 * flight.
 *
 * {@code synonyms} is keyed by field path (dot-separated for a sum
 * type's variant fields) -- optional matching hints for the mapping
 * agent, never empty-vs-null ambiguous (an absent {@code synonyms:}
 * block in the source YAML parses to an empty map, not null).
 */
public record CanonicalModel(
        String modelId,
        int version,
        TargetConfig target,
        CanonicalType root,
        Map<String, List<String>> synonyms,
        Path sourceFile) {
}
