package com.alai.agenticsheets.mapping;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plain, fast unit tests -- no Docker, no Spring context, no
 * Testcontainers. Split across the split the classes themselves took
 * (an external review's finding that the journal needed to be a
 * separate, conditionally-absent bean, not baked into the always-active
 * controller): {@link FakeTargetJournal} is tested directly as a plain
 * object, and {@link FakeTargetController} is tested with both an
 * absent and a present journal to confirm {@code receive()} behaves
 * identically either way -- the whole point of {@code Optional<FakeTargetJournal>}
 * constructor injection.
 */
class FakeTargetControllerTest {

    @Test
    void journalCapturesHeadersRegardlessOfCasingSent() {
        FakeTargetJournal journal = new FakeTargetJournal();

        // Mixed case, deliberately not matching the controller's
        // canonical lowercase capture keys -- exactly the shape a real
        // request actually sent, per Dispatcher's own .header(...)
        // calls. FakeTargetController does the case-insensitive
        // matching before calling record(); this test exercises the
        // journal with already-normalized (lowercase) keys, matching
        // what the controller actually passes it.
        Map<String, String> alreadyNormalizedHeaders = Map.of(
                "x-import-batch-id", "1",
                "x-mapping-proposal-id", "2",
                "idempotency-key", "abc123");

        journal.record("holdings", "[]", alreadyNormalizedHeaders);

        List<FakeTargetJournal.ReceivedRequest> requests = journal.findAll("holdings");
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).headers().get("x-import-batch-id")).isEqualTo("1");
        assertThat(requests.get(0).headers().get("x-mapping-proposal-id")).isEqualTo("2");
        assertThat(requests.get(0).headers().get("idempotency-key")).isEqualTo("abc123");
    }

    @Test
    void requestsAccumulateAcrossMultipleDeliveries() {
        FakeTargetJournal journal = new FakeTargetJournal();
        journal.record("holdings", "[1]", Map.of());
        journal.record("holdings", "[2]", Map.of());

        assertThat(journal.findAll("holdings")).hasSize(2);
        assertThat(journal.findAll("holdings").get(0).body()).isEqualTo("[1]");
        assertThat(journal.findAll("holdings").get(1).body()).isEqualTo("[2]");
    }

    @Test
    void resetClearsTheJournal() {
        FakeTargetJournal journal = new FakeTargetJournal();
        journal.record("holdings", "[1]", Map.of());

        journal.reset();

        assertThat(journal.findAll("holdings")).isEmpty();
    }

    @Test
    void findAllForAnUnknownServiceIsEmptyNotNull() {
        FakeTargetJournal journal = new FakeTargetJournal();
        assertThat(journal.findAll("nonexistent")).isEmpty();
    }

    @Test
    void journalIsBoundedPerServiceOldestDroppedFirst() {
        FakeTargetJournal journal = new FakeTargetJournal();
        for (int i = 0; i < 150; i++) {
            journal.record("holdings", "[" + i + "]", Map.of());
        }

        List<FakeTargetJournal.ReceivedRequest> requests = journal.findAll("holdings");
        assertThat(requests).hasSize(100);
        // Oldest (body "[0]" through "[49]") should have been dropped;
        // the most recent 100 remain, in order.
        assertThat(requests.get(0).body()).isEqualTo("[50]");
        assertThat(requests.get(requests.size() - 1).body()).isEqualTo("[149]");
    }

    @Test
    void receiveWorksIdenticallyWithNoJournalPresent() {
        // The actual point of Optional<FakeTargetJournal> constructor
        // injection: with the journal bean absent (matching a normal,
        // non-E2E deployment where journal-enabled is unset), receive()
        // still works and returns the same success response -- it just
        // has nothing to record into.
        FakeTargetController controller = new FakeTargetController(Optional.empty());

        Map<String, Object> response = controller.receive("holdings", "[]", Map.of("X-Import-Batch-Id", "1"));

        assertThat(response.get("received")).isEqualTo(true);
        assertThat(response.get("service")).isEqualTo("holdings");
    }

    @Test
    void receiveRecordsIntoTheJournalWhenPresent() {
        FakeTargetJournal journal = new FakeTargetJournal();
        FakeTargetController controller = new FakeTargetController(Optional.of(journal));

        controller.receive("holdings", "[]", Map.of("X-Import-Batch-Id", "1", "Authorization", "Bearer super-secret"));

        List<FakeTargetJournal.ReceivedRequest> requests = journal.findAll("holdings");
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).headers().get("x-import-batch-id")).isEqualTo("1");
        // Authorization is deliberately never captured, even though
        // this is local-only testing infrastructure -- no reason to
        // make a real secret queryable and end up printed in a test
        // failure's output.
        assertThat(requests.get(0).headers()).doesNotContainKey("authorization");
        assertThat(requests.get(0).headers().values()).noneMatch(value -> value.contains("super-secret"));
    }
}
