package com.alai.agenticsheets.canonical;

import java.util.Map;

/**
 * Sum type (OR): exactly one entry in {@code variants} is present in a
 * valid value, never zero, never more than one. A sum type where every
 * variant is an empty {@code RecordType} is structurally just an enum --
 * see {@code Currency} in the sample canonical models.
 */
public record SumType(String name, Map<String, RecordType> variants) implements CanonicalType {
}
