package com.alai.agenticsheets.mapping;

import java.util.List;
import java.util.Map;

/**
 * What the mapping agent proposes for one source file against one
 * canonical model -- bound directly from the chat model's structured
 * output (see {@link MappingProposalService}).
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
     */
    public record FieldMapping(
            String canonicalFieldPath,
            String sourceColumn,
            String sourceConstant,
            String selectedVariant,
            Map<String, String> variantValueMap,
            double confidence,
            String conversionNotes) {
    }
}
