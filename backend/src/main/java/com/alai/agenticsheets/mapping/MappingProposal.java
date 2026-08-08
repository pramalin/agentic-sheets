package com.alai.agenticsheets.mapping;

import java.util.List;
import java.util.Map;

/**
 * What the mapping agent proposes for one source file against one
 * canonical model -- bound directly from the chat model's structured
 * output (see {@link AgentMappingProposalService}).
 *
 * Deliberately generic rather than typed to any one canonical model's
 * shape: canonical models are loaded from YAML at runtime (see
 * {@code CanonicalModelRegistry}), so there's no fixed Java class that
 * could represent "a Holdings row" vs "a MarketRateBookValue row" at
 * compile time. Every field reference here is a flattened field path
 * (dot-separated for a sum type variant's fields, e.g.
 * {@code asset_class.FixedIncome.maturity_date}), matching exactly what
 * {@link CanonicalModelPromptRenderer} shows the agent.
 */
public record MappingProposal(
        List<FieldMapping> fieldMappings,
        List<String> unmappedSourceColumns,
        String summary) {

    /**
     * Normalizes a {@code null} list to empty at construction, regardless
     * of which path constructs a {@code MappingProposal} -- Spring AI's
     * structured-output decode of the chat model's response, JSONB
     * deserialization of an already-persisted proposal, or a hand-built
     * {@code /amend} request body. Found the hard way: a real Qwen 2.5 3B
     * response, faced with a genuinely unfamiliar column (Local LLM
     * phase, Step LLM-6 -- see {@code docs/local-llm-enhancements.md}),
     * decoded to a proposal with {@code fieldMappings: null} rather than
     * an empty list, which crashed {@link SumTypeMappingResolver#resolve}
     * with an unhandled {@link NullPointerException} that leaked to the
     * HTTP boundary as a raw 500 -- not a clean, reported validation
     * failure the way a malformed proposal should always be. Every
     * current call site that iterates {@link #fieldMappings()}
     * ({@link MappingProposalStructuralValidator}, {@link SumTypeMappingResolver},
     * {@link ProposalValidationService}, {@link MappingMemoryEligibility})
     * assumed non-null; normalizing here, once, at the type boundary,
     * closes that for all of them at once rather than patching each call
     * site individually and risking missing one, which is exactly how
     * this gap existed in the first place -- {@code SumTypeMappingResolver}
     * itself had two separate unguarded uses.
     *
     * <p>Deliberately normalizes to empty rather than rejecting outright
     * here: an empty list is a legitimate, well-defined state
     * ({@link MappingProposalStructuralValidator} already treats it as
     * an explicit problem to report, not a silent pass), whereas
     * throwing from a record's compact constructor would surface as an
     * unhelpfully generic exception at an unpredictable point in Spring
     * AI's decode pipeline rather than this project's normal, structured
     * validation-problem reporting.
     */
    public MappingProposal {
        if (fieldMappings == null) {
            fieldMappings = List.of();
        }
        if (unmappedSourceColumns == null) {
            unmappedSourceColumns = List.of();
        }
    }

    /**
     * One canonical field's proposed source. Exactly one of
     * {@code sourceColumn} or {@code sourceConstant} should be set, never
     * both -- a per-row column mapping, or a constant derived from
     * something like a banner row (see {@code holdings_metlife}'s
     * as-of-date).
     *
     * When {@code canonicalFieldPath} is itself a sum type field (e.g.
     * {@code asset_class}), exactly one of {@code selectedVariant} or
     * {@code variantValueMap} should be set, never both:
     * {@code selectedVariant} when every row in this file is the same
     * fixed variant (e.g. a file containing only fixed-income
     * positions); {@code variantValueMap} (raw source value -> variant
     * name, e.g. {@code "Equity" -> "Equity"}, {@code "Fixed Income" ->
     * "FixedIncome"}) when the variant genuinely depends on each row's
     * own data, which is the common case for a column like "Asset
     * Class" holding different values per row.
     *
     * {@code transformations} is empty for the common case (the raw
     * value is already in the canonical field's expected unit/shape).
     * Added after an external review correctly identified a real
     * silent-corruption risk: {@code conversionNotes} is free text a
     * human reads, never interpreted by {@link CanonicalRowBuilder} --
     * so a note saying "divide by 100" (e.g. PIMCO's market rate stored
     * as a percentage like "5.375" needing to become the canonical
     * fraction 0.05375) never actually happened, and the wrong value
     * would pass validation and get delivered. Deliberately scoped to
     * one transformation type ({@code scale}) rather than a general
     * transformation DSL -- that's the one concretely-motivated case;
     * see {@code mapping-notes.md}'s Step 7.1 notes for what's
     * deliberately not built yet (trim/replace/lookup) and why.
     */
    public record FieldMapping(
            String canonicalFieldPath,
            String sourceColumn,
            String sourceConstant,
            String selectedVariant,
            Map<String, String> variantValueMap,
            List<TransformationStep> transformations,
            double confidence,
            String conversionNotes) {
    }

    /**
     * A single, LLM-proposable but deterministically-interpreted
     * transformation step. Deliberately a flat record with a string
     * {@code type} discriminator, not a sealed-interface hierarchy --
     * Spring AI's structured output binds directly to
     * {@link MappingProposal} via a fixed-schema Java record (see the
     * README's design principles on why the agent's output isn't
     * ADT-bound), and a flat shape is trivial to bind reliably the same
     * way every other field on {@link FieldMapping} already is; a
     * polymorphic sealed-interface field would need Jackson
     * discriminator configuration this project hasn't verified works
     * correctly with Spring AI's schema generation, and this project has
     * already been burned enough times this year guessing at unfamiliar
     * framework behavior instead of checking it.
     *
     * {@link CanonicalRowBuilder} whitelists and interprets {@code type}
     * itself -- an unrecognized type, or a type applied to a field kind
     * it doesn't make sense for, is a validation error, never silently
     * ignored or silently applied.
     *
     * Only {@code "scale"} is currently implemented: {@code multiplier}
     * is a decimal string multiplied into an already-parsed
     * {@code NUMBER} value (e.g. {@code multiplier: "0.01"} to convert a
     * percentage like {@code "5.375"} into the fraction {@code 0.05375}).
     */
    public record TransformationStep(String type, String multiplier) {
    }
}
