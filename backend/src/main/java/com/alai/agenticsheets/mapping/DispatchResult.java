package com.alai.agenticsheets.mapping;

/** Outcome of dispatching a batch's valid rows to a team's configured
  * target -- {@code SUCCESS}, {@code TERMINAL_FAILURE} (won't retry,
  * per {@code target.delivery}'s classification), or
  * {@code RETRIES_EXHAUSTED} (kept hitting retryable failures until
  * {@code maxAttempts} ran out). */
public record DispatchResult(Outcome outcome, int attempts, Integer lastStatusCode, String message) {

    public enum Outcome { SUCCESS, TERMINAL_FAILURE, RETRIES_EXHAUSTED, NOT_IMPLEMENTED }
}
