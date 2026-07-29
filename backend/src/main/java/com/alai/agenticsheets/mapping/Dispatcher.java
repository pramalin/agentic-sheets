package com.alai.agenticsheets.mapping;

import com.alai.agenticsheets.canonical.CanonicalValue;
import com.alai.agenticsheets.canonical.DeliveryConfig;
import com.alai.agenticsheets.canonical.TargetConfig;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

/**
 * Sends a batch's already-validated canonical rows to the team's
 * configured {@code target}, with retry/backoff/rejection classification
 * per {@code target.delivery}. Every attempt is recorded in
 * {@code delivery_log} via {@link DeliveryLogRepository} regardless of
 * outcome.
 *
 * Only {@code transport: rest} with {@code auth.type: api-key} is
 * actually implemented -- the one combination the sample configs
 * actually exercise ({@code holdings.yaml}). {@code transport: mcp}
 * would need creating an MCP client connection to an arbitrary
 * team-specified endpoint at dispatch time, a materially different (and
 * harder) thing than the static, compose-time-configured connection this
 * project has to {@code sheets-reader-mcp} -- deferred rather than
 * guessed at. {@code oauth2-client-credentials} and {@code mtls} auth
 * are real flows this doesn't attempt to fake; both fail fast with a
 * clear "not yet implemented" rather than silently sending an
 * unauthenticated request. See {@code mapping-notes.md}'s Step 7 notes.
 *
 * Uses the JDK's own {@code java.net.http.HttpClient} rather than a
 * Spring HTTP abstraction -- deliberately: it needs no new dependency,
 * and its API has been stable since JDK 11, which matters after this
 * project's repeated experience guessing wrong about exact framework API
 * shapes it couldn't verify without a real build.
 *
 * Synchronous: a retry loop blocks the calling request thread for the
 * duration of backoff delays. Acceptable for a manually-triggered,
 * single-operator prototype; a production version would want this
 * running asynchronously off a queue instead of inline with the approval
 * HTTP call. Not built now -- flagged, not hidden.
 */
@Service
public class Dispatcher {

    /** Explicit three-way (plus a safe default) classification, pulled
      * out as its own pure function so it's directly testable without a
      * real HTTP call -- an external review correctly caught that the
      * original implementation never actually consulted
      * {@code retryableStatusCodes} at all, silently retrying anything
      * not explicitly terminal (including redirects, or an
      * authentication failure a model's config forgot to list as
      * terminal). Anything not explicitly classified either way falls
      * back to a status-code-range default (5xx retryable, everything
      * else terminal) rather than defaulting to "keep retrying forever,"
      * since retrying something that will provably never succeed is more
      * dangerous than surfacing it as a failure needing attention. */
    enum Classification { SUCCESS, RETRYABLE, TERMINAL }

    static Classification classify(int status, DeliveryConfig delivery) {
        if (status >= 200 && status < 300) {
            return Classification.SUCCESS;
        }
        if (delivery.terminalStatusCodes().contains(status)) {
            return Classification.TERMINAL;
        }
        if (delivery.retryableStatusCodes().contains(status)) {
            return Classification.RETRYABLE;
        }
        return (status >= 500 && status < 600) ? Classification.RETRYABLE : Classification.TERMINAL;
    }

    private final DeliveryLogRepository deliveryLogRepository;
    private final JsonMapper jsonMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    public Dispatcher(DeliveryLogRepository deliveryLogRepository, JsonMapper jsonMapper) {
        this.deliveryLogRepository = deliveryLogRepository;
        this.jsonMapper = jsonMapper;
    }

    public DispatchResult dispatch(long importBatchId, long mappingProposalId, TargetConfig target,
            List<CanonicalValue> validRows) {
        if (!"rest".equals(target.transport())) {
            String message = "transport '" + target.transport() + "' is not yet implemented -- only 'rest' is";
            deliveryLogRepository.record(importBatchId, mappingProposalId, 1, target.transport(), "NOT_IMPLEMENTED", null, message);
            return new DispatchResult(DispatchResult.Outcome.NOT_IMPLEMENTED, 1, null, message);
        }
        if (!"api-key".equals(target.authType())) {
            String message = "auth.type '" + target.authType() + "' is not yet implemented -- only 'api-key' is";
            deliveryLogRepository.record(importBatchId, mappingProposalId, 1, target.transport(), "NOT_IMPLEMENTED", null, message);
            return new DispatchResult(DispatchResult.Outcome.NOT_IMPLEMENTED, 1, null, message);
        }

        // Fail before any network call, not with a silently-empty bearer
        // credential -- an external review correctly caught that a
        // missing secret used to produce "Authorization: Bearer " (empty)
        // rather than a clear configuration error.
        String secret = System.getenv(target.secretRef());
        if (secret == null || secret.isBlank()) {
            String message = "DELIVERY_CONFIGURATION_ERROR: environment variable '" + target.secretRef()
                    + "' is not set -- refusing to send an unauthenticated request";
            deliveryLogRepository.record(importBatchId, mappingProposalId, 1, target.transport(), "CONFIGURATION_ERROR", null, message);
            return new DispatchResult(DispatchResult.Outcome.CONFIGURATION_ERROR, 1, null, message);
        }

        String payload = jsonMapper.writeValueAsString(
                validRows.stream().map(CanonicalValueJson::toJsonCompatible).toList());

        // A stable key a receiver *could* dedupe a retried delivery
        // against -- an external review correctly noted that
        // X-Import-Batch-Id/X-Mapping-Proposal-Id identify *what* this
        // delivery is, but don't formally instruct a receiver to treat
        // two deliveries with the same key as the same operation. Same
        // proposal + same payload (rows only change if the proposal or
        // source data changed, either of which produces a different
        // hash) always yields the same key, so a naive retry of an
        // otherwise-identical delivery is dedupeable even without a full
        // transactional outbox on this side -- that piece is still
        // deferred, this is a cheap, real step toward it, not a
        // substitute for it.
        String idempotencyKey = mappingProposalId + ":" + sha256Hex(payload);

        DeliveryConfig delivery = target.delivery();

        for (int attempt = 1; attempt <= delivery.maxAttempts(); attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(target.endpoint()))
                        .timeout(REQUEST_TIMEOUT)
                        .header("Content-Type", "application/json")
                        .header("X-Import-Batch-Id", String.valueOf(importBatchId))
                        .header("X-Mapping-Proposal-Id", String.valueOf(mappingProposalId))
                        .header("Idempotency-Key", idempotencyKey)
                        .header("Authorization", "Bearer " + secret)
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                Classification classification = classify(status, delivery);

                if (classification == Classification.SUCCESS) {
                    deliveryLogRepository.record(importBatchId, mappingProposalId, attempt, "rest", "SUCCESS", status, null);
                    return new DispatchResult(DispatchResult.Outcome.SUCCESS, attempt, status, "delivered");
                }
                if (classification == Classification.TERMINAL) {
                    deliveryLogRepository.record(importBatchId, mappingProposalId, attempt, "rest", "TERMINAL_FAILURE", status, response.body());
                    return new DispatchResult(DispatchResult.Outcome.TERMINAL_FAILURE, attempt, status, response.body());
                }
                deliveryLogRepository.record(importBatchId, mappingProposalId, attempt, "rest", "RETRYABLE_FAILURE", status, response.body());
            } catch (InterruptedException e) {
                // Stop immediately, don't fall through into another sleep
                // + retry -- an external review correctly caught that the
                // original code restored the interrupt flag but kept
                // retrying anyway.
                Thread.currentThread().interrupt();
                deliveryLogRepository.record(importBatchId, mappingProposalId, attempt, "rest", "RETRYABLE_FAILURE", null, "interrupted");
                return new DispatchResult(DispatchResult.Outcome.INTERRUPTED, attempt, null, "delivery interrupted");
            } catch (IOException e) {
                deliveryLogRepository.record(importBatchId, mappingProposalId, attempt, "rest", "RETRYABLE_FAILURE", null, e.getMessage());
            }

            if (attempt < delivery.maxAttempts()) {
                if (!sleep(backoffSeconds(delivery, attempt))) {
                    return new DispatchResult(DispatchResult.Outcome.INTERRUPTED, attempt, null, "delivery interrupted during backoff");
                }
            }
        }

        return new DispatchResult(DispatchResult.Outcome.RETRIES_EXHAUSTED, delivery.maxAttempts(), null,
                "exhausted " + delivery.maxAttempts() + " attempts without success");
    }

    private long backoffSeconds(DeliveryConfig delivery, int attempt) {
        if ("exponential".equals(delivery.backoff())) {
            long delay = delivery.initialDelaySeconds() * (1L << (attempt - 1));
            return Math.min(delay, delivery.maxDelaySeconds());
        }
        return Math.min(delivery.initialDelaySeconds(), delivery.maxDelaySeconds());
    }

    private String sha256Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed present on every standard JVM, same
            // reasoning as FileHasher's identical catch clause.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** @return false if interrupted during the sleep (caller should stop
      * immediately), true if the sleep completed normally. */
    private boolean sleep(long seconds) {
        try {
            Thread.sleep(Duration.ofSeconds(seconds));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
