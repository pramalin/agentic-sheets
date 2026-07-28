package com.alai.agenticsheets.canonical;

import java.nio.file.Path;

/**
 * One team's config, fully parsed and validated -- what
 * {@link CanonicalModelRegistry} holds and everything else in the
 * codebase reads. {@code version} is load-bearing: pinned on a
 * {@code mapping_proposal} when it's created, and part of the
 * {@code mapping_memory} cache key, so a later config change doesn't
 * retroactively affect work already in flight.
 */
public record CanonicalModel(
        String modelId,
        int version,
        TargetConfig target,
        CanonicalType root,
        Path sourceFile) {
}
