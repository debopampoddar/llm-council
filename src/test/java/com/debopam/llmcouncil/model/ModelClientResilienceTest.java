package com.debopam.llmcouncil.model;

import com.debopam.llmcouncil.orchestration.StageType;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.retry.TransientAiException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ModelClientResilienceTest {

    @Test
    void retriesTransientFailuresAndReturnsRecoveredResult() {
        AtomicInteger attempts = new AtomicInteger();
        ModelClient delegate = request -> {
            if (attempts.incrementAndGet() < 3) {
                throw failure(ModelFailureCategory.PROVIDER_UNAVAILABLE);
            }
            return new ModelCallResult("recovered", 1L, 2L, Duration.ZERO);
        };

        ModelCallResult result = new RetryableModelClient(delegate, 2, Duration.ZERO).call(request(Duration.ofSeconds(1)));
        assertEquals("recovered", result.text());
        assertEquals(3, attempts.get());
    }

    @Test
    void deterministicFailuresAreNeverRetried() {
        for (ModelFailureCategory category : List.of(
                ModelFailureCategory.MODEL_NOT_FOUND,
                ModelFailureCategory.CONFIGURATION_ERROR,
                ModelFailureCategory.INVALID_MODEL_OUTPUT,
                ModelFailureCategory.MODEL_CALL_FAILED)) {
            AtomicInteger attempts = new AtomicInteger();
            ModelClient delegate = request -> {
                attempts.incrementAndGet();
                throw failure(category);
            };
            assertThrows(ModelCallException.class,
                    () -> new RetryableModelClient(delegate, 3, Duration.ZERO)
                            .call(request(Duration.ofSeconds(1))));
            assertEquals(1, attempts.get(), category.name());
        }
    }

    @Test
    void retryConfigurationRejectsInvalidBounds() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new RetryableModelClient(req -> null, -1, Duration.ZERO)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new RetryableModelClient(req -> null, 1, null)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new RetryableModelClient(req -> null, 1, Duration.ofMillis(-1))));
    }

    @Test
    void springAiAdapterEnforcesThePerCallTimeout() {
        ChatModel blocking = prompt -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return null;
        };
        SpringAiModelClient client = new SpringAiModelClient("slow", ChatClient.create(blocking));

        long started = System.nanoTime();
        ModelCallException failure = assertThrows(ModelCallException.class,
                () -> client.call(request(Duration.ofMillis(50))));
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertEquals(ModelFailureCategory.MODEL_TIMEOUT, failure.category());
        assertTrue(elapsedMillis < 1_000, "configured timeout was ignored; elapsed=" + elapsedMillis);
    }

    @Test
    void springAiAdapterMakesOneProviderCallPerLogicalRequest() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel counting = prompt -> {
            calls.incrementAndGet();
            return new ChatResponse(List.of(new Generation(new AssistantMessage("one response"))));
        };
        SpringAiModelClient client = new SpringAiModelClient(
                "counting", ChatClient.create(counting));

        ModelCallResult result = client.call(request(Duration.ofSeconds(1)));

        assertEquals("one response", result.text());
        assertEquals(1, calls.get(), "one logical request must make exactly one provider call");
    }

    @Test
    void springAiAdapterRejectsAnEmptyProviderResponse() {
        ChatModel empty = prompt -> new ChatResponse(
                List.of(new Generation(new AssistantMessage("   "))));
        SpringAiModelClient client = new SpringAiModelClient("empty", ChatClient.create(empty));

        ModelCallException failure = assertThrows(ModelCallException.class,
                () -> client.call(request(Duration.ofSeconds(1))));

        assertEquals(ModelFailureCategory.INVALID_MODEL_OUTPUT, failure.category());
        assertTrue(failure.getMessage().contains("empty response"));
    }

    @Test
    void springAiAdapterSkipsAnthropicReasoningAndReturnsVisibleText() {
        ChatModel anthropicThinking = prompt -> new ChatResponse(List.of(
                new Generation(AssistantMessage.builder()
                        .content("private reasoning")
                        .properties(Map.of("signature", "opaque"))
                        .build()),
                new Generation(new AssistantMessage("visible answer"))));
        SpringAiModelClient client = new SpringAiModelClient(
                "anthropic", ChatClient.create(anthropicThinking), false);

        ModelCallResult result = client.call(request(Duration.ofSeconds(1)));

        assertEquals("visible answer", result.text());
    }

    @Test
    void springAiTransientFailuresRemainRetryable() {
        ChatModel unavailable = prompt -> {
            throw new TransientAiException("provider overloaded");
        };
        SpringAiModelClient client = new SpringAiModelClient(
                "unavailable", ChatClient.create(unavailable));

        ModelCallException failure = assertThrows(ModelCallException.class,
                () -> client.call(request(Duration.ofSeconds(1))));

        assertEquals(ModelFailureCategory.PROVIDER_UNAVAILABLE, failure.category());
    }

    @Test
    void springAiAdapterCanOmitTemperatureForProvidersThatRejectIt() {
        AtomicReference<Double> capturedTemperature = new AtomicReference<>();
        ChatModel inspecting = prompt -> {
            capturedTemperature.set(prompt.getOptions().getTemperature());
            throw new IllegalStateException("captured");
        };
        SpringAiModelClient client = new SpringAiModelClient(
                "claude", ChatClient.create(inspecting), false);

        assertThrows(ModelCallException.class,
                () -> client.call(request(Duration.ofSeconds(1))));

        assertNull(capturedTemperature.get());
    }

    @Test
    void springAiAdapterIncludesTemperatureByDefault() {
        AtomicReference<Double> capturedTemperature = new AtomicReference<>();
        ChatModel inspecting = prompt -> {
            capturedTemperature.set(prompt.getOptions().getTemperature());
            throw new IllegalStateException("captured");
        };
        SpringAiModelClient client = new SpringAiModelClient(
                "openai", ChatClient.create(inspecting));

        assertThrows(ModelCallException.class,
                () -> client.call(request(Duration.ofSeconds(1))));

        assertEquals(0.2, capturedTemperature.get());
    }

    @Test
    void springAiAdapterUsesGpt5CompatibleOpenAiOutputLimit() {
        AtomicReference<OpenAiChatOptions> capturedOptions = new AtomicReference<>();
        ChatModel inspecting = prompt -> {
            capturedOptions.set((OpenAiChatOptions) prompt.getOptions());
            throw new IllegalStateException("captured");
        };
        SpringAiModelClient client = new SpringAiModelClient(
                "openai-gpt5", ChatClient.create(inspecting), false, true);

        assertThrows(ModelCallException.class,
                () -> client.call(request(Duration.ofSeconds(1))));

        assertEquals(100, capturedOptions.get().getMaxCompletionTokens());
        assertNull(capturedOptions.get().getMaxTokens());
        assertNull(capturedOptions.get().getTemperature());
    }

    @Test
    void ollamaPayloadRejectsInvalidBaseUrlAtConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new OllamaDirectModelClient("model", "not a uri with spaces"));
    }

    private ModelCallRequest request(Duration timeout) {
        return new ModelCallRequest("session", StageType.GENERATE, "model", "provider-model",
                List.of(new ChatMessage("user", "hello")), 100, 0.2, false, timeout);
    }

    private ModelCallException failure(ModelFailureCategory category) {
        return new ModelCallException(category, "test", "model", category.name());
    }
}
