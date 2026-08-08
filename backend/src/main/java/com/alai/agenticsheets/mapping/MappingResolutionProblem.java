package com.alai.agenticsheets.mapping;

/**
 * One thing {@link SumTypeMappingResolver} (Local LLM phase, Step LLM-2 --
 * see {@code docs/local-llm-enhancements.md}) found while resolving or
 * validating a sum type field's variant. Deliberately typed rather than a
 * plain {@code String}, so a future review UI can distinguish these
 * without re-parsing message text.
 *
 * <p>All four {@link Kind}s are defined now even though Step LLM-2's
 * resolver -- which has no dependency on client configuration -- only
 * ever constructs {@link Kind#UNRESOLVED} and {@link Kind#SEMANTIC_CONFLICT}.
 * {@link Kind#CLIENT_CONFIGURATION} and {@link Kind#CONFIGURED_OVERRIDE_NOTABLE}
 * exist so the type doesn't need to change shape again once client
 * conventions (Step LLM-3) and their integration into resolution (Step
 * LLM-4) land -- only which {@code Kind}s get emitted grows, not the
 * record itself.
 *
 * @param blocking whether this problem should prevent the proposal from
 * being accepted. True for every kind except {@link Kind#CONFIGURED_OVERRIDE_NOTABLE},
 * which flags a configured client convention that diverges from what
 * canonical-name matching alone would have produced -- worth surfacing
 * (eventually to a human, via Step LLM-5's "Confirm?" affordance) but not
 * grounds to reject a proposal on its own, since an explicit, approved
 * client convention is allowed to legitimately disagree with a
 * coincidental name match.
 */
public record MappingResolutionProblem(
        Kind kind,
        String canonicalFieldPath,
        String sourceColumn,
        String message,
        boolean blocking) {

    public enum Kind {
        /** Missing or unresolvable, and not uniquely derivable -- left alone rather than guessed. */
        UNRESOLVED,
        /** Model-supplied (or configured) variant metadata contradicts what was actually observed in the data. */
        SEMANTIC_CONFLICT,
        /** A client-configured convention (Step LLM-3+) is itself invalid against the current canonical model. */
        CLIENT_CONFIGURATION,
        /** A configured convention's target diverges from canonical-name matching -- notable, not blocking. */
        CONFIGURED_OVERRIDE_NOTABLE,
    }
}
