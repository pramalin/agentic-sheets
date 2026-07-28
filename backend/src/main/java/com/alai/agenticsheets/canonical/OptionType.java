package com.alai.agenticsheets.canonical;

/** The {@code ?} suffix in the DSL -- {@code Type?} means {@code Option[Type]}. */
public record OptionType(CanonicalType inner) implements CanonicalType {
}
