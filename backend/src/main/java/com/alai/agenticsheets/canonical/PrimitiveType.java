package com.alai.agenticsheets.canonical;

/**
 * {@code format} is nullable and means "use the kind's default" -- for
 * {@code DATE} that default is ISO-8601 {@code yyyy-MM-dd}; the other
 * kinds have no formatting concept yet. This is always the
 * canonical/output format (what gets rendered into the JSON payload sent
 * to a team's service) -- it has nothing to do with how any client's raw
 * spreadsheet happens to format its own values. See
 * {@code client-configs/*.yaml} and {@code ClientConfig} for that.
 */
public record PrimitiveType(Kind kind, String format) implements CanonicalType {

    public enum Kind { STRING, NUMBER, DATE, BOOLEAN }

    public static final String DEFAULT_DATE_FORMAT = "yyyy-MM-dd";
}
