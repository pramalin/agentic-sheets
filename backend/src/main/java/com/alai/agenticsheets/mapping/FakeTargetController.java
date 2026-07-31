package com.alai.agenticsheets.mapping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * FOR LOCAL TESTING ONLY. Simulates a team's receiving service so
 * {@link Dispatcher} has something real to call without needing external
 * network access or a second running service -- {@code holdings.yaml}'s
 * {@code target.endpoint} points here for exactly that reason. A real
 * team's target lives entirely outside this project, on infrastructure
 * this system has no knowledge of (see {@code SCHEMA.md}'s "Target
 * service" section) -- this endpoint exists purely so Step 7's dispatch
 * path is actually exercisable, not as a model for how a real receiver
 * should be built.
 *
 * The request journal (added alongside Step 8d's golden-path E2E test,
 * per an external review's finding) exists for the same reason llmsim's
 * own journal does: {@code Dispatcher} reporting {@code SUCCESS} only
 * proves it received a 2xx, never that the payload it sent was actually
 * correct -- a regression sending an empty array, wrong field values, or
 * missing headers would report success identically. Recording every
 * request and letting a test read the list back -- deliberately a full
 * list, not just the most recent one, matching {@code GET
 * /_llmsim/calls}'s exact shape -- turns "dispatch succeeded" into an
 * assertable "the right data crossed the delivery boundary exactly
 * once," the same "exactly once" property llmsim's own journal already
 * makes assertable for the model-call side of this same pipeline.
 */
@RestController
@RequestMapping("/internal/fake-target")
public class FakeTargetController {

    private static final Logger log = LoggerFactory.getLogger(FakeTargetController.class);

    /** Only the headers a test would actually want to assert on --
      * deliberately not Authorization, even though this is local-only
      * testing infrastructure; no reason to make a real secret
      * queryable and end up printed in a test failure's output. */
    private static final List<String> CAPTURED_HEADERS =
            List.of("x-import-batch-id", "x-mapping-proposal-id", "idempotency-key", "content-type");

    private final Map<String, List<ReceivedRequest>> requestsByService = new ConcurrentHashMap<>();

    @PostMapping("/{service}")
    public Map<String, Object> receive(
            @PathVariable String service,
            @RequestBody String body,
            @RequestHeader Map<String, String> headers) {
        // Case-insensitive matching, deliberately -- HTTP header names
        // are case-insensitive by protocol (RFC 7230), and a real run
        // of this exact code proved why that matters here: Dispatcher
        // sends "X-Import-Batch-Id" (mixed case), and an exact-string
        // Map.get("x-import-batch-id") against whatever casing Spring
        // actually preserved came back null. The fix isn't "use the
        // right casing" -- there isn't one right casing to hardcode,
        // any client or proxy could send any case -- it's to never
        // assume a specific casing when matching header names at all.
        Map<String, String> captured = new ConcurrentHashMap<>();
        for (String headerName : CAPTURED_HEADERS) {
            headers.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(headerName))
                    .findFirst()
                    .ifPresent(entry -> captured.put(headerName, entry.getValue()));
        }

        // Logged from `captured`, not a fresh lookup against the raw
        // `headers` map -- the same case-sensitivity mistake this
        // method's actual capture logic just got fixed for would
        // otherwise still be sitting right here, printing a misleading
        // "null" in the log even though the real capture works
        // correctly. `captured`'s keys are always CAPTURED_HEADERS'
        // canonical lowercase names, guaranteed by the loop above.
        log.info("fake-target[{}] received {} bytes, X-Import-Batch-Id={}",
                service, body.length(), captured.get("x-import-batch-id"));

        requestsByService
                .computeIfAbsent(service, key -> new CopyOnWriteArrayList<>())
                .add(new ReceivedRequest(body, captured, Instant.now()));

        return Map.of("received", true, "service", service, "bytes", body.length());
    }

    /**
     * Every request {@code service} has received since the last reset,
     * oldest first -- the read side of the journal a test asserts
     * against, mirroring {@code GET /_llmsim/calls}'s exact shape for
     * the model-call side of the same pipeline (a full list, so both
     * "what was received" and "exactly how many times" are directly
     * assertable, not just the most recent one).
     */
    @GetMapping("/{service}/requests")
    public List<ReceivedRequest> requests(@PathVariable String service) {
        return requestsByService.getOrDefault(service, List.of());
    }

    /**
     * Clears the journal for every service -- lets one running instance
     * serve multiple test cases without restarting the container,
     * matching {@code POST /_llmsim/reset}'s same role for the model
     * side.
     */
    @PostMapping("/reset")
    public void reset() {
        requestsByService.clear();
    }

    public record ReceivedRequest(String body, Map<String, String> headers, Instant receivedAt) {
    }
}
