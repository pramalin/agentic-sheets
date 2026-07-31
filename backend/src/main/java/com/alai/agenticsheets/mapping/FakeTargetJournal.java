package com.alai.agenticsheets.mapping;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The fake-target request journal -- a real security finding from an
 * external review, not a hypothetical: recording full delivered
 * payloads (which, for a real client, would be real financial data) in
 * an unbounded, unauthenticated-by-design map is fine for the isolated,
 * throwaway E2E environment it was built for, but would be a genuine
 * exposure and a memory-growth risk if this configuration ever reached
 * a shared or hosted environment.
 *
 * {@code @ConditionalOnProperty} means this bean, and everything that
 * depends on it, simply doesn't exist in the Spring context unless
 * {@code agentic-sheets.fake-target.journal-enabled} is explicitly
 * true -- enabled only in {@code compose.e2e.yaml}. That's a stronger
 * guarantee than an auth check would be: a request to the journal's
 * read/reset endpoints in a normal deployment gets a plain 404 (no
 * matching handler at all), not a 401 from a check that could itself
 * have a bug. {@link FakeTargetController#receive} still works
 * everywhere, always (it's the actual echo behavior {@link Dispatcher}
 * needs for any local testing, not just automated E2E) -- it just has
 * nothing to record into when this bean isn't present.
 *
 * Bounded per service (oldest dropped first), mirroring llmsim's own
 * {@code LLMSIM_JOURNAL_MAX_ENTRIES} precedent -- even in the E2E
 * environment this is meant for, an unbounded journal across many test
 * runs sharing one long-lived container is still worth capping.
 */
@Component
@ConditionalOnProperty(name = "agentic-sheets.fake-target.journal-enabled", havingValue = "true")
public class FakeTargetJournal {

    private static final int MAX_ENTRIES_PER_SERVICE = 100;

    private final Map<String, List<ReceivedRequest>> requestsByService = new ConcurrentHashMap<>();

    public void record(String service, String body, Map<String, String> headers) {
        List<ReceivedRequest> requests = requestsByService.computeIfAbsent(service, key -> new CopyOnWriteArrayList<>());
        requests.add(new ReceivedRequest(body, headers, Instant.now()));
        while (requests.size() > MAX_ENTRIES_PER_SERVICE) {
            requests.remove(0);
        }
    }

    public List<ReceivedRequest> findAll(String service) {
        return requestsByService.getOrDefault(service, List.of());
    }

    public void reset() {
        requestsByService.clear();
    }

    public record ReceivedRequest(String body, Map<String, String> headers, Instant receivedAt) {
    }
}
