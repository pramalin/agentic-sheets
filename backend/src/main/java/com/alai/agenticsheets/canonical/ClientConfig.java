package com.alai.agenticsheets.canonical;

/**
 * One client's {@code client-configs/<client>.yaml} -- source-side
 * parsing conventions (what format that client's raw spreadsheets use),
 * as distinct from a canonical model's config, which governs the
 * *output* shape. Deliberately flat, not an ADT -- there's no
 * product/sum structure to a handful of parsing hints. Expected to grow
 * more fields (decimal separator, default currency, ...) as more client
 * quirks show up; see {@code canonical-models/SCHEMA.md}'s "Source
 * conventions" section.
 */
public record ClientConfig(
        String clientId,
        String dateFormat) {
}
