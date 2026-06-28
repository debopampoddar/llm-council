package com.debopam.llmcouncil.model;

import com.debopam.llmcouncil.orchestration.StageType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RetryableModelClient} — validates retry logic, backoff,
 * non-retryable propagation, and edge cases.
 */
class RetryableModelClientTest {

    @Test
    void successOnFirstAttemptReturnsImmediately() {
        ModelClient inner = request -> new ModelCallResult("ok", 10L, 20L, Duration.ofMillis(1));
        RetryableModelClient retryable = new RetryableModelClient(inner, 3, Duration.ofMillis(1));

        ModelCallResult result = retryable.call(dummyRequest());
        assertEquals("ok", result.text());
    }

    @Test
    void retriesOnTransientFailureAndSucceeds() {
        AtomicInteger attempts = new AtomicInteger(0);
        ModelClient inner = request -> {
            if (attempts.incrementAndGet() <= 2) {
                throw new ModelCallException(
                        ModelFailureCategory.PROVIDER_UNAVAILABLE, "test", "model", "unavailable");
            }
            return new ModelCallResult("recovered", 10L, 20L, Duration.ofMillis(1));
        };

        // maxRetries=2 means 3 total attempts — enough for 2 failures then success
        RetryableModelClient retryable = new RetryableModelClient(inner, 2, Duration.ofMillis(1));
        ModelCallResult result = retryable.call(dummyRequest());

        assertEquals("recovered", result.text());
        assertEquals(3, attempts.get(), "Should have attempted 3 times");
    }

    @Test
    void throwsAfterMaxRetriesExhausted() {
        ModelClient inner = request -> {
            throw new ModelCallException(
                    ModelFailureCategory.MODEL_TIMEOUT, "test", "model", "timeout");
        };

        RetryableModelClient retryable = new RetryableModelClient(inner, 1, Duration.ofMillis(1));

        ModelCallException ex = assertThrows(ModelCallException.class,
                () -> retryable.call(dummyRequest()));
        assertEquals(ModelFailureCategory.MODEL_TIMEOUT, ex.category());
    }

    @Test
    void nonRetryableFailureIsPropagatedImmediately() {
        AtomicInteger attempts = new AtomicInteger(0);
        ModelClient inner = request -> {
            attempts.incrementAndGet();
            throw new ModelCallException(
                    ModelFailureCategory.MODEL_NOT_FOUND, "test", "model", "not found");
        };

        RetryableModelClient retryable = new RetryableModelClient(inner, 5, Duration.ofMillis(1));

        ModelCallException ex = assertThrows(ModelCallException.class,
                () -> retryable.call(dummyRequest()));
        assertEquals(ModelFailureCategory.MODEL_NOT_FOUND, ex.category());
        assertEquals(1, attempts.get(), "Non-retryable should NOT retry");
    }

    @Test
    void configurationErrorIsNonRetryable() {
        AtomicInteger attempts = new AtomicInteger(0);
        ModelClient inner = request -> {
            attempts.incrementAndGet();
            throw new ModelCallException(
                    ModelFailureCategory.CONFIGURATION_ERROR, "test", "model", "bad config");
        };

        RetryableModelClient retryable = new RetryableModelClient(inner, 3, Duration.ofMillis(1));

        assertThrows(ModelCallException.class, () -> retryable.call(dummyRequest()));
        assertEquals(1, attempts.get(), "CONFIGURATION_ERROR should NOT retry");
    }

    @Test
    void invalidModelOutputIsNonRetryable() {
        AtomicInteger attempts = new AtomicInteger(0);
        ModelClient inner = request -> {
            attempts.incrementAndGet();
            throw new ModelCallException(
                    ModelFailureCategory.INVALID_MODEL_OUTPUT, "test", "model", "bad output");
        };

        RetryableModelClient retryable = new RetryableModelClient(inner, 3, Duration.ofMillis(1));

        assertThrows(ModelCallException.class, () -> retryable.call(dummyRequest()));
        assertEquals(1, attempts.get(), "INVALID_MODEL_OUTPUT should NOT retry");
    }

    @Test
    void zeroRetriesMeansOneAttemptOnly() {
        AtomicInteger attempts = new AtomicInteger(0);
        ModelClient inner = request -> {
            attempts.incrementAndGet();
            throw new ModelCallException(
                    ModelFailureCategory.PROVIDER_UNAVAILABLE, "test", "model", "down");
        };

        RetryableModelClient retryable = new RetryableModelClient(inner, 0, Duration.ofMillis(1));

        assertThrows(ModelCallException.class, () -> retryable.call(dummyRequest()));
        assertEquals(1, attempts.get(), "maxRetries=0 means exactly 1 attempt");
    }

    @Test
    void constructorRejectsNegativeRetries() {
        assertThrows(IllegalArgumentException.class,
                () -> new RetryableModelClient(r -> null, -1, Duration.ofMillis(1)));
    }

    @Test
    void constructorRejectsNullBaseDelay() {
        assertThrows(IllegalArgumentException.class,
                () -> new RetryableModelClient(r -> null, 1, null));
    }

    private ModelCallRequest dummyRequest() {
        return new ModelCallRequest("session-1", StageType.GENERATE, "test-model",
                                    "provider-model", List.of(), 100, 0.3, false,
                                    Duration.ofSeconds(10));
    }
}
