package com.alai.agenticsheets.canonical;

/**
 * The runtime representation every team's {@code canonical-models/*.yaml}
 * parses into. See {@code canonical-models/SCHEMA.md} for the format
 * these mirror: {@link PrimitiveType} (String/Number/Date/Boolean),
 * {@link RecordType} (product type -- AND), {@link SumType} (sum type --
 * OR, tagged variants), and {@link OptionType} (the {@code ?} suffix).
 *
 * {@link CanonicalModelParser} is the only code that builds these from
 * raw YAML; everything else in the codebase consumes this typed
 * representation, never the raw text. See {@link CanonicalModelRegistry}.
 */
public sealed interface CanonicalType
        permits PrimitiveType, RecordType, SumType, OptionType {
}
