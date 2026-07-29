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
import java.time.Duration;
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

    private final DeliveryLogRepository deliveryLogRepository;
    private final JsonMapper jsonMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public Dispatcher(DeliveryLogRepository deliveryLogRepository, JsonMapper jsonMapper) {
        this.deliveryLogRepository = deliveryLogRepository;
        this.jsonMapper = jsonMapper;
    }

    public DispatchResult dispatch(long importBatchId, TargetConfig target, List<CanonicalValue> validRows) {
        if (!"rest".equals(target.transport())) {
            String message = "transport '" + target.transport() + "' is not yet implemented -- only 'rest' is";
            deliveryLogRepository.record(importBatchId, 1, target.transport(), "NOT_IMPLEMENTED", null, message);
            return new DispatchResult(DispatchResult.Outcome.NOT_IMPLEMENTED, 0, null, message);
        }
        if (!"api-key".equals(target.authType())) {
            String message = "auth.type '" + target.authType() + "' is not yet implemented -- only 'api-key' is";
            deliveryLogRepository.record(importBatchId, 1, target.transport(), "NOT_IMPLEMENTED", null, message);
            return new DispatchResult(DispatchResult.Outcome.NOT_IMPLEMENTED, 0, null, message);
        }

        String payload = jsonMapper.writeValueAsString(
                validRows.stream().map(CanonicalValueJson::toJsonCompatible).toList());

        String secret = System.getenv(target.secretRef());
        DeliveryConfig delivery = target.delivery();

        for (int attempt = 1; attempt <= delivery.maxAttempts(); attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(target.endpoint()))
                        .header("Content-Type", "application/json")
                        .header("X-Import-Batch-Id", String.valueOf(importBatchId))
                        .header("Authorization", "Bearer " + (secret == null ? "" : secret))
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();

                if (status >= 200 && status < 300) {
                    deliveryLogRepository.record(importBatchId, attempt, "rest", "SUCCESS", status, null);
                    return new DispatchResult(DispatchResult.Outcome.SUCCESS, attempt, status, "delivered");
                }
                if (delivery.terminalStatusCodes().contains(status)) {
                    deliveryLogRepository.record(importBatchId, attempt, "rest", "TERMINAL_FAILURE", status, response.body());
                    return new DispatchResult(DispatchResult.Outcome.TERMINAL_FAILURE, attempt, status, response.body());
                }
                // Retryable if explicitly listed, or unclassified -- see
                // SCHEMA.md's documented default (retry 5xx, terminal 4xx).
                deliveryLogRepository.record(importBatchId, attempt, "rest", "RETRYABLE_FAILURE", status, response.body());
            } catch (IOException | InterruptedException e) {
                deliveryLogRepository.record(importBatchId, attempt, "rest", "RETRYABLE_FAILURE", null, e.getMessage());
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }

            if (attempt < delivery.maxAttempts()) {
                sleep(backoffSeconds(delivery, attempt));
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

    private void sleep(long seconds) {
        try {
            Thread.sleep(Duration.ofSeconds(seconds));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
