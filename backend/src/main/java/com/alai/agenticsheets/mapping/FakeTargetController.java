package com.alai.agenticsheets.mapping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

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
 * Recording to {@link FakeTargetJournal} is optional and conditional --
 * see that class's own javadoc for why the journal is a separate,
 * {@code @ConditionalOnProperty}-gated bean rather than living directly
 * in this always-active controller: an external review correctly flagged
 * that a journal exposing full delivered payloads through an
 * unauthenticated read endpoint is a real risk if this configuration
 * ever reached a shared or hosted environment, not just local/E2E use.
 * {@code Optional<FakeTargetJournal>} constructor injection means
 * {@link #receive} works identically either way -- it just has nothing
 * to record into when the journal bean doesn't exist.
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

    private final Optional<FakeTargetJournal> journal;

    public FakeTargetController(Optional<FakeTargetJournal> journal) {
        this.journal = journal;
    }

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
        // method's actual capture logic was fixed for would otherwise
        // still be sitting right here, printing a misleading "null" in
        // the log even though the real capture works correctly.
        log.info("fake-target[{}] received {} bytes, X-Import-Batch-Id={}",
                service, body.length(), captured.get("x-import-batch-id"));

        journal.ifPresent(j -> j.record(service, body, captured));

        return Map.of("received", true, "service", service, "bytes", body.length());
    }
}
