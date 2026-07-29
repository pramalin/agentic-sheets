package com.alai.agenticsheets.canonical;

/** The {@code Option} "None" case -- a genuinely absent optional field,
  * or the placeholder value {@link com.alai.agenticsheets.mapping.CanonicalRowBuilder}
  * substitutes at a construction site that failed (the real problem is
  * recorded separately as an error, not encoded in this value). */
public record AbsentValue() implements CanonicalValue {
}
