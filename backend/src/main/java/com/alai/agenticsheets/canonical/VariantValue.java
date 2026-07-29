package com.alai.agenticsheets.canonical;

/** Exactly one variant selected, carrying exactly that variant's payload
  * -- there's no way to construct a {@code VariantValue} with two cases
  * filled in, or with a case name the {@link SumType} it's checked
  * against doesn't declare (the JVM type system enforces the first;
  * {@link com.alai.agenticsheets.mapping.CanonicalRowBuilder} enforces
  * the second at construction time). */
public record VariantValue(String caseName, RecordValue payload) implements CanonicalValue {
}
