package com.alai.agenticsheets.canonical;

import java.util.Map;

/**
 * Product type (AND): every entry in {@code fields} is present in a
 * valid value. {@code name} is carried through mainly for error messages
 * and debug output -- a sum type's variants are themselves
 * {@code RecordType}s named {@code "<SumTypeName>.<VariantName>"}.
 */
public record RecordType(String name, Map<String, CanonicalType> fields) implements CanonicalType {
}
