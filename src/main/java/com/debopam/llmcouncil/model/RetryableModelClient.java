package com.debopam.llmcouncil.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Decorator that adds retry-with-exponential-backoff to any {@link ModelClient}.
 *
 * <p>Only <em>transient</em> failures are retried — categories where the provider
 * might recover on a subsequent attempt:
 * <ul>
 *   <li>{@link ModelFailureCategory#PROVIDER_UNAVAILABLE}</li>
 *   <li>{@link ModelFailureCategory#MODEL_TIMEOUT}</li>
 * </ul>
 *
 * <p>Deterministic failures such as {@code MODEL_NOT_FOUND},
 * {@code CONFIGURATION_ERROR}, and {@code INVALID_MODEL_OUTPUT} are immediately
 * propagated — retrying them would be wasteful.
 *
 * <p><strong>Backoff formula:</strong>
 * {@code delay = baseDelay × 2^attempt + random(0–500 ms)}
 *
 * <p>Example with defaults ({@code maxRetries=2, baseDelay=1 s}):
 * <pre>
 *   Attempt 0 → immediate call
 *   Attempt 1 → ~1 000–1 500 ms wait, then retry
 *   Attempt 2 → ~2 000–2 500 ms wait, then retry
 *   → give up, throw last exception
 * </pre>
 *
 * @see ModelClient
 * @see ModelCallException
 */
public class RetryableModelClient implements ModelClient {

    private static final Logger log = LoggerFactory.getLogger(RetryableModelClient.class);

    /** Maximum random jitter added to each backoff delay. */
    private static final long JITTER_BOUND_MS = 500L;

    /** Failure categories where a retry has a realistic chance of succeeding. */
    private static final Set<ModelFailureCategory> RETRYABLE_CATEGORIES = Set.of(
            ModelFailureCategory.PROVIDER_UNAVAILABLE,
            ModelFailureCategory.MODEL_TIMEOUT
    );

    private final ModelClient delegate;
    private final int maxRetries;
    private final Duration baseDelay;

    /**
     * Wraps the given client with retry logic.
     *
     * @param delegate  the underlying {@link ModelClient} to delegate calls to
     * @param maxRetries maximum number of retry attempts (0 = no retries)
     * @param baseDelay  base delay before the first retry; doubled on each
     *                   subsequent attempt
     * @throws IllegalArgumentException if {@code maxRetries < 0} or
     *                                  {@code baseDelay} is null/negative
     */
    public RetryableModelClient(ModelClient delegate, int maxRetries, Duration baseDelay) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be >= 0, got: " + maxRetries);
        }
        if (baseDelay == null || baseDelay.isNegative()) {
            throw new IllegalArgumentException("baseDelay must be non-null and non-negative");
        }
        this.delegate = delegate;
        this.maxRetries = maxRetries;
        this.baseDelay = baseDelay;
    }

    /**
     * Calls the delegate, retrying on transient failures up to {@code maxRetries}
     * times with exponential backoff and jitter.
     *
     * @param request the model call request
     * @return the successful result from the delegate
     * @throws ModelCallException if all attempts are exhausted or the failure
     *                            category is non-retryable
     */
    @Override
    public ModelCallResult call(ModelCallRequest request) throws ModelCallException {
        ModelCallException lastException = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return delegate.call(request);
            } catch (ModelCallException ex) {
                lastException = ex;

                // Non-retryable failures are propagated immediately — no point
                // hammering a provider that will deterministically reject the call.
                if (!isRetryable(ex.category())) {
                    log.debug("Non-retryable failure for model {}: category={}, message={}",
                              request.modelId(), ex.category(), ex.getMessage());
                    throw ex;
                }

                // If we have retries left, back off and try again.
                if (attempt < maxRetries) {
                    long delayMs = computeBackoffMs(attempt);
                    log.warn("Transient failure for model {} (attempt {}/{}): {}. "
                             + "Retrying in {} ms…",
                             request.modelId(), attempt + 1, maxRetries + 1,
                             ex.getMessage(), delayMs);
                    sleep(delayMs);
                } else {
                    // Final attempt also failed — fall through to throw below.
                    log.error("All {} attempts exhausted for model {}: {}",
                              maxRetries + 1, request.modelId(), ex.getMessage());
                }
            }
        }

        // Should only be reached when maxRetries >= 0 and every attempt threw.
        throw lastException;
    }

    /**
     * Determines whether a failure category warrants a retry.
     *
     * @param category the failure category from the exception
     * @return {@code true} if the failure is considered transient
     */
    private static boolean isRetryable(ModelFailureCategory category) {
        return RETRYABLE_CATEGORIES.contains(category);
    }

    /**
     * Computes the backoff delay for the given attempt using exponential growth
     * plus random jitter.
     *
     * <p>Formula: {@code baseDelay × 2^attempt + random(0, JITTER_BOUND_MS)}
     *
     * @param attempt zero-based attempt index (0 = first retry)
     * @return delay in milliseconds
     */
    private long computeBackoffMs(int attempt) {
        // Shift left is equivalent to 2^attempt multiplication.
        long exponentialMs = baseDelay.toMillis() * (1L << attempt);
        long jitterMs = ThreadLocalRandom.current().nextLong(JITTER_BOUND_MS);
        return exponentialMs + jitterMs;
    }

    /**
     * Sleeps for the specified duration, restoring the interrupt flag if the
     * thread is interrupted during the wait.
     *
     * @param millis duration to sleep in milliseconds
     */
    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            // Restore interrupt flag so callers higher up can detect it.
            Thread.currentThread().interrupt();
            throw new ModelCallException(
                    ModelFailureCategory.UNKNOWN, null, null,
                    "Retry sleep interrupted", ie);
        }
    }
}
