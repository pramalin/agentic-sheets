package com.alai.agenticsheets.canonical;

import java.util.List;

/**
 * Per-team retry/rejection policy for delivering an approved canonical
 * payload to that team's service. All fields have defaults ({@link
 * #defaults()}) so most teams never need to write a {@code delivery:}
 * block at all -- only override what needs to differ.
 */
public record DeliveryConfig(
        int maxAttempts,
        String backoff,             // "exponential" | "fixed"
        int initialDelaySeconds,
        int maxDelaySeconds,
        List<Integer> retryableStatusCodes,
        List<Integer> terminalStatusCodes) {

    public static DeliveryConfig defaults() {
        return new DeliveryConfig(
                5, "exponential", 2, 60,
                List.of(502, 503, 504),
                List.of(400, 401, 403, 404, 409, 422));
    }
}
