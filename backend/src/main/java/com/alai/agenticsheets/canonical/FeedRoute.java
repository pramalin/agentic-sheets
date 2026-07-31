package com.alai.agenticsheets.canonical;

import java.util.List;

/**
 * One entry in a client's {@code feeds:} map -- Step 9's inbox scanner
 * uses this to turn a parsed {@code (clientId, feedType)} pair from a
 * filename into a canonical model and an expected worksheet name.
 * Deliberately a {@link ClientConfig} concern, not a
 * {@link CanonicalModel} one, despite referencing a model id: this is
 * source-side routing (how *this client's* files map onto a model), the
 * same distinction {@link ClientConfig} already draws for
 * {@code dateFormat} and other source conventions -- a canonical
 * model's own config governs the *output* shape and has no reason to
 * know which clients feed it or what they call their worksheets.
 *
 * {@code worksheetNames} is a list, not a single string, because
 * different clients (or the same client over time) may use slightly
 * different worksheet names for conceptually the same feed -- matched
 * exactly, case-sensitively, against what {@code sheets-reader-mcp}
 * reports; the scanner fails deterministically (quarantines, does not
 * guess) when zero or more than one of a workbook's actual worksheets
 * matches.
 */
public record FeedRoute(String feedType, String modelId, List<String> worksheetNames) {
}
