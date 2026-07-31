package com.alai.agenticsheets.canonical;

import java.util.Map;

/**
 * One client's {@code client-configs/<client>.yaml} -- source-side
 * parsing conventions (what format that client's raw spreadsheets use),
 * as distinct from a canonical model's config, which governs the
 * *output* shape. Expected to grow more fields (decimal separator,
 * default currency, ...) as more client quirks show up; see
 * {@code canonical-models/SCHEMA.md}'s "Source conventions" section.
 *
 * {@code feeds} (Step 9) is the same source-side-concern reasoning
 * applied to inbox-scanner routing: {@code feedType -> FeedRoute}, empty
 * for a client that never submits through the scanner. See
 * {@link FeedRoute}'s own javadoc for why this lives here and not on
 * {@link CanonicalModel}.
 */
public record ClientConfig(
        String clientId,
        String dateFormat,
        Map<String, FeedRoute> feeds) {
}
