package com.alai.agenticsheets.canonical;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeliveryConfigTest {

    @Test
    void defaultsAreValid() {
        assertThat(DeliveryConfig.defaults()).isNotNull();
    }

    @Test
    void rejectsMaxAttemptsBelowOne() {
        assertThatThrownBy(() -> new DeliveryConfig(0, "exponential", 1, 10, List.of(503), List.of(400)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxAttempts");
    }

    @Test
    void rejectsAnUnreasonablyLargeMaxAttemptsToPreventBackoffOverflow() {
        assertThatThrownBy(() -> new DeliveryConfig(21, "exponential", 1, 10, List.of(503), List.of(400)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxAttempts");
    }

    @Test
    void rejectsAnUnrecognizedBackoffStrategy() {
        assertThatThrownBy(() -> new DeliveryConfig(3, "linear", 1, 10, List.of(503), List.of(400)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("backoff");
    }

    @Test
    void rejectsNegativeDelays() {
        assertThatThrownBy(() -> new DeliveryConfig(3, "fixed", -1, 10, List.of(503), List.of(400)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("initialDelaySeconds");

        assertThatThrownBy(() -> new DeliveryConfig(3, "fixed", 1, -10, List.of(503), List.of(400)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxDelaySeconds");
    }

    @Test
    void rejectsInitialDelayGreaterThanMaxDelay() {
        assertThatThrownBy(() -> new DeliveryConfig(3, "fixed", 100, 10, List.of(503), List.of(400)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("initialDelaySeconds");
    }

    @Test
    void rejectsStatusCodesOutsideTheValidHttpRange() {
        assertThatThrownBy(() -> new DeliveryConfig(3, "fixed", 1, 10, List.of(50), List.of(400)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retryableStatusCodes");

        assertThatThrownBy(() -> new DeliveryConfig(3, "fixed", 1, 10, List.of(503), List.of(600)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("terminalStatusCodes");
    }

    @Test
    void rejectsTheSameCodeInBothRetryableAndTerminalLists() {
        assertThatThrownBy(() -> new DeliveryConfig(3, "fixed", 1, 10, List.of(503, 429), List.of(429, 400)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("overlap");
    }

    @Test
    void allowsAWellFormedCustomConfig() {
        DeliveryConfig config = new DeliveryConfig(3, "fixed", 1, 10, List.of(429), List.of(400));
        assertThat(config.maxAttempts()).isEqualTo(3);
    }
}
