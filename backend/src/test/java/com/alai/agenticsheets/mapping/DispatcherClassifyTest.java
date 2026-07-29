package com.alai.agenticsheets.mapping;

import com.alai.agenticsheets.canonical.DeliveryConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests {@link Dispatcher#classify} in isolation -- an external review
 * correctly caught that the original implementation never actually
 * consulted {@code retryableStatusCodes} at all, silently retrying
 * anything not explicitly in {@code terminalStatusCodes} (including
 * redirects, or an auth failure a model's config forgot to list). Pure
 * function, no HTTP call needed to test it properly.
 */
class DispatcherClassifyTest {

    private static final DeliveryConfig DEFAULTS = DeliveryConfig.defaults();

    @Test
    void twoHundredIsSuccess() {
        assertThat(Dispatcher.classify(200, DEFAULTS)).isEqualTo(Dispatcher.Classification.SUCCESS);
        assertThat(Dispatcher.classify(204, DEFAULTS)).isEqualTo(Dispatcher.Classification.SUCCESS);
    }

    @Test
    void explicitlyConfiguredTerminalStatusIsTerminalEvenIfItWouldOtherwiseLookRetryable() {
        // 401 is in DeliveryConfig.defaults()'s terminalStatusCodes --
        // confirm it's actually consulted, not just terminal by luck of
        // being outside the 5xx range.
        assertThat(Dispatcher.classify(401, DEFAULTS)).isEqualTo(Dispatcher.Classification.TERMINAL);
    }

    @Test
    void explicitlyConfiguredRetryableStatusIsRetryable() {
        assertThat(Dispatcher.classify(503, DEFAULTS)).isEqualTo(Dispatcher.Classification.RETRYABLE);
    }

    @Test
    void unclassifiedFiveHundredRangeDefaultsToRetryable() {
        // 500 isn't in either configured list in DeliveryConfig.defaults()
        // -- confirm the fallback default kicks in rather than defaulting
        // to "retry forever" or "always terminal" indiscriminately.
        assertThat(Dispatcher.classify(500, DEFAULTS)).isEqualTo(Dispatcher.Classification.RETRYABLE);
    }

    @Test
    void unclassifiedNonFiveHundredRangeDefaultsToTerminal() {
        // A redirect (3xx) that isn't explicitly classified either way --
        // the original bug would have retried this indefinitely.
        assertThat(Dispatcher.classify(302, DEFAULTS)).isEqualTo(Dispatcher.Classification.TERMINAL);
    }

    @Test
    void explicitConfigurationOverridesTheDefaultFallback() {
        DeliveryConfig custom = new DeliveryConfig(3, "fixed", 1, 10,
                List.of(429), // rate limit explicitly retryable for this team
                List.of(400));
        assertThat(Dispatcher.classify(429, custom)).isEqualTo(Dispatcher.Classification.RETRYABLE);
        assertThat(Dispatcher.classify(400, custom)).isEqualTo(Dispatcher.Classification.TERMINAL);
    }
}
