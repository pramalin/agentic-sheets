package com.alai.agenticsheets.mapping;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FakeTargetController has no external dependencies, so this is a
 * plain, fast unit test -- no Docker, no Testcontainers, unlike most of
 * the coverage for this project's delivery pipeline.
 *
 * The case-insensitive header matching specifically exists because a
 * real E2E run found a real bug: {@link Dispatcher} sends
 * {@code X-Import-Batch-Id} (mixed case), and an earlier version of
 * {@link FakeTargetController} did an exact-string
 * {@code headers.get("x-import-batch-id")} lookup that came back null
 * against whatever casing Spring actually preserved. HTTP header names
 * are case-insensitive by protocol (RFC 7230) -- the fix isn't "use the
 * right casing" (there isn't one to hardcode; any client or proxy could
 * send any case), it's matching case-insensitively regardless of what
 * casing actually arrives.
 */
class FakeTargetControllerTest {

    @Test
    void capturesHeadersRegardlessOfCasingSent() {
        FakeTargetController controller = new FakeTargetController();

        // Mixed case, deliberately not matching CAPTURED_HEADERS'
        // canonical lowercase names -- exactly the shape a real request
        // actually sent, per Dispatcher's own .header(...) calls.
        Map<String, String> incomingHeaders = Map.of(
                "X-Import-Batch-Id", "1",
                "X-Mapping-Proposal-Id", "2",
                "Idempotency-Key", "abc123",
                "Content-Type", "application/json",
                "Authorization", "Bearer super-secret");

        controller.receive("holdings", "[]", incomingHeaders);

        List<FakeTargetController.ReceivedRequest> requests = controller.requests("holdings");
        assertThat(requests).hasSize(1);

        Map<String, String> captured = requests.get(0).headers();
        assertThat(captured.get("x-import-batch-id")).isEqualTo("1");
        assertThat(captured.get("x-mapping-proposal-id")).isEqualTo("2");
        assertThat(captured.get("idempotency-key")).isEqualTo("abc123");
        assertThat(captured.get("content-type")).isEqualTo("application/json");
    }

    @Test
    void neverCapturesAuthorization() {
        FakeTargetController controller = new FakeTargetController();
        Map<String, String> incomingHeaders = Map.of(
                "X-Import-Batch-Id", "1",
                "Authorization", "Bearer super-secret");

        controller.receive("holdings", "[]", incomingHeaders);

        Map<String, String> captured = controller.requests("holdings").get(0).headers();
        assertThat(captured).doesNotContainKey("authorization");
        assertThat(captured.values()).noneMatch(value -> value.contains("super-secret"));
    }

    @Test
    void requestsAccumulateAcrossMultipleDeliveries() {
        FakeTargetController controller = new FakeTargetController();
        controller.receive("holdings", "[1]", Map.of());
        controller.receive("holdings", "[2]", Map.of());

        assertThat(controller.requests("holdings")).hasSize(2);
        assertThat(controller.requests("holdings").get(0).body()).isEqualTo("[1]");
        assertThat(controller.requests("holdings").get(1).body()).isEqualTo("[2]");
    }

    @Test
    void resetClearsTheJournal() {
        FakeTargetController controller = new FakeTargetController();
        controller.receive("holdings", "[1]", Map.of());

        controller.reset();

        assertThat(controller.requests("holdings")).isEmpty();
    }

    @Test
    void requestsForAnUnknownServiceIsEmptyNotNull() {
        FakeTargetController controller = new FakeTargetController();
        assertThat(controller.requests("nonexistent")).isEmpty();
    }
}
