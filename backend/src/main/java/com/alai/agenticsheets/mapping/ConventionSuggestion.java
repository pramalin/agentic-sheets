package com.alai.agenticsheets.mapping;

import java.time.OffsetDateTime;

/**
 * One {@code convention_suggestion} row -- see that table's own schema
 * comment for the full reasoning. Local LLM phase, Step LLM-5 (see
 * {@code docs/local-llm-enhancements.md}).
 */
public record ConventionSuggestion(
        long id,
        long sourceProposalId,
        String clientId,
        String modelId,
        String kind,
        String canonicalFieldPath,
        String sourceValue,
        String targetVariant,
        String status,
        String suggestedBy,
        OffsetDateTime createdAt,
        OffsetDateTime resolvedAt) {

    public static final String KIND_FIELD_ALIAS = "FIELD_ALIAS";
    public static final String KIND_VARIANT_VALUE = "VARIANT_VALUE";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPLIED = "APPLIED";
    public static final String STATUS_DISMISSED = "DISMISSED";
}
