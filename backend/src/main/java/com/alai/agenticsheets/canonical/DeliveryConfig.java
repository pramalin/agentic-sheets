package com.alai.agenticsheets.canonical;

import java.util.List;

/**
 * Per-team retry/rejection policy for delivering an approved canonical
 * payload to that team's service. All fields have defaults ({@link
 * #defaults()}) so most teams never need to write a {@code delivery:}
 * block at all -- only override what needs to differ.
 *
 * The compact constructor enforces the invariants an external review
 * correctly pointed out weren't being checked anywhere: without them,
 * {@code maxAttempts <= 0} silently produces no HTTP call and no
 * attempt log at all (indistinguishable from a working config that just
 * never got exercised), and an unbounded {@code maxAttempts} can
 * overflow {@code Dispatcher}'s exponential-backoff shift calculation.
 * Malformed config now fails at load time (surfaced the same way any
 * other bad {@code canonical-models/*.yaml} file does --
 * {@code CanonicalModelRegistry} keeps the previous good version and
 * logs the failure) rather than at delivery time, against a real batch.
 */
public record DeliveryConfig(
        int maxAttempts,
        String backoff,             // "exponential" | "fixed"
        int initialDelaySeconds,
        int maxDelaySeconds,
        List<Integer> retryableStatusCodes,
        List<Integer> terminalStatusCodes) {

    public DeliveryConfig {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("delivery.maxAttempts must be at least 1, got " + maxAttempts);
        }
        if (maxAttempts > 20) {
            // Not a real-world retry count anyone actually wants -- this
            // exists specifically to keep the value small enough that
            // Dispatcher's `initialDelaySeconds * (1L << (attempt - 1))`
            // exponential-backoff shift can never overflow.
            throw new IllegalArgumentException("delivery.maxAttempts must be at most 20, got " + maxAttempts);
        }
        if (!"exponential".equals(backoff) && !"fixed".equals(backoff)) {
            throw new IllegalArgumentException(
                    "delivery.backoff must be 'exponential' or 'fixed', got '" + backoff + "'");
        }
        if (initialDelaySeconds < 0) {
            throw new IllegalArgumentException(
                    "delivery.initialDelaySeconds must be non-negative, got " + initialDelaySeconds);
        }
        if (maxDelaySeconds < 0) {
            throw new IllegalArgumentException(
                    "delivery.maxDelaySeconds must be non-negative, got " + maxDelaySeconds);
        }
        if (initialDelaySeconds > maxDelaySeconds) {
            throw new IllegalArgumentException(
                    "delivery.initialDelaySeconds (" + initialDelaySeconds
                            + ") must not be greater than maxDelaySeconds (" + maxDelaySeconds + ")");
        }
        requireValidHttpStatusCodes(retryableStatusCodes, "retryableStatusCodes");
        requireValidHttpStatusCodes(terminalStatusCodes, "terminalStatusCodes");
        java.util.Set<Integer> overlap = new java.util.HashSet<>(retryableStatusCodes);
        overlap.retainAll(terminalStatusCodes);
        if (!overlap.isEmpty()) {
            throw new IllegalArgumentException(
                    "delivery.retryableStatusCodes and terminalStatusCodes must not overlap, both contain " + overlap);
        }
    }

    private static void requireValidHttpStatusCodes(List<Integer> codes, String fieldName) {
        for (Integer code : codes) {
            if (code == null || code < 100 || code > 599) {
                throw new IllegalArgumentException(
                        "delivery." + fieldName + " contains " + code + ", which is not a valid HTTP status code "
                                + "(100-599)");
            }
        }
    }

    public static DeliveryConfig defaults() {
        return new DeliveryConfig(
                5, "exponential", 2, 60,
                List.of(502, 503, 504),
                List.of(400, 401, 403, 404, 409, 422));
    }
}
