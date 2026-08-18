package com.debopam.llmcouncil.application;

import com.debopam.llmcouncil.api.dto.ModelProbeRequest;
import com.debopam.llmcouncil.model.ModelCallException;
import com.debopam.llmcouncil.model.ModelCallRequest;
import com.debopam.llmcouncil.model.ModelCallResult;
import com.debopam.llmcouncil.model.ModelFailureCategory;
import com.debopam.llmcouncil.model.ProbeModelClientFactory;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelProbeServiceTest {

    @Test
    void aSuccessfulProbeUsesTheFixedBoundedRequestAndReturnsUsage() {
        AtomicReference<ModelCallRequest> captured = new AtomicReference<>();
        AtomicLong ticker = new AtomicLong(1_000_000_000L);
        ProbeModelClientFactory factory = (provider, model) -> request -> {
            captured.set(request);
            return new ModelCallResult("OK", 12L, 1L, Duration.ofMillis(37));
        };
        ModelProbeService service = service(factory, ticker);

        var response = service.probe(new ModelProbeRequest("openai", "gpt-4.1-mini", true));

        assertTrue(response.reachable());
        assertEquals("OK", response.status());
        assertEquals(37L, response.latencyMs());
        assertEquals(12L, response.promptTokens());
        assertEquals(1L, response.completionTokens());
        assertEquals("gpt-4.1-mini", captured.get().providerModelId());
        assertEquals(8, captured.get().maxOutputTokens());
        assertEquals(Duration.ofSeconds(20), captured.get().timeout());
        assertEquals(0.0, captured.get().temperature());
        assertEquals(2, captured.get().messages().size());
    }

    @Test
    void providerFailureIsAStableResultAndDoesNotEchoTheUnderlyingMessage() {
        ProbeModelClientFactory factory = (provider, model) -> request -> {
            throw new ModelCallException(ModelFailureCategory.MODEL_NOT_FOUND, provider, model,
                                         "secret provider response that must stay in logs");
        };
        ModelProbeService service = service(factory, new AtomicLong(1));

        var response = service.probe(new ModelProbeRequest("ollama", "missing:latest", false));

        assertFalse(response.reachable());
        assertEquals("MODEL_NOT_FOUND", response.status());
        assertTrue(response.detail().contains("does not expose"));
        assertFalse(response.detail().contains("secret provider response"));
    }

    @Test
    void cloudCallCannotStartWithoutExplicitAcknowledgement() {
        AtomicInteger factoryCalls = new AtomicInteger();
        ModelProbeService service = service((provider, model) -> {
            factoryCalls.incrementAndGet();
            return request -> new ModelCallResult("OK");
        }, new AtomicLong(1));

        ModelProbeRequestException refusal = assertThrows(ModelProbeRequestException.class,
                () -> service.probe(new ModelProbeRequest("anthropic", "claude-sonnet-4-20250514", false)));

        assertTrue(refusal.getMessage().contains("acknowledgement"));
        assertEquals(0, factoryCalls.get());
    }

    @Test
    void mockUnknownAndMalformedModelIdsAreOutsideTheBoundary() {
        ModelProbeService service = service((provider, model) -> request -> new ModelCallResult("OK"),
                                            new AtomicLong(1));

        assertThrows(ModelProbeRequestException.class,
                () -> service.probe(new ModelProbeRequest("mock", "anything", true)));
        assertThrows(ModelProbeRequestException.class,
                () -> service.probe(new ModelProbeRequest("ollama", "bad model\nname", false)));
        assertThrows(ModelProbeRequestException.class, () -> service.probe(null));
    }

    @Test
    void cooldownIsGlobalAndReportsWhenAnotherProbeMayRun() {
        AtomicLong ticker = new AtomicLong(1_000_000_000L);
        AtomicInteger calls = new AtomicInteger();
        ModelProbeService service = service((provider, model) -> request -> {
            calls.incrementAndGet();
            return new ModelCallResult("OK");
        }, ticker);

        service.probe(new ModelProbeRequest("ollama", "llama3.1:8b", false));
        ModelProbeThrottledException throttled = assertThrows(ModelProbeThrottledException.class,
                () -> service.probe(new ModelProbeRequest("ollama", "mistral:7b", false)));

        assertEquals(10L, throttled.retryAfterSeconds());
        assertEquals(1, calls.get());

        ticker.addAndGet(Duration.ofSeconds(10).toNanos());
        service.probe(new ModelProbeRequest("ollama", "mistral:7b", false));
        assertEquals(2, calls.get());
    }

    @Test
    void firstProbeWorksWhenTheJvmNanoTimeOriginIsNegative() {
        AtomicLong ticker = new AtomicLong(-Duration.ofDays(2).toNanos());
        ModelProbeService service = service((provider, model) -> request -> new ModelCallResult("OK"),
                                            ticker);

        assertTrue(service.probe(new ModelProbeRequest("ollama", "llama3.1:8b", false)).reachable());
    }

    private ModelProbeService service(ProbeModelClientFactory factory, AtomicLong ticker) {
        return new ModelProbeService(factory, Duration.ofSeconds(10), Duration.ofSeconds(20),
                                     ticker::get);
    }
}
