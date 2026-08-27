package com.debopam.llmcouncil.model;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.retry.TransientAiException;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Spring AI-backed model client.
 *
 * <p>The project uses this adapter for OpenAI, Anthropic, and Gemini provider
 * beans. It preserves system/user separation, binds the provider model per
 * request, and can omit sampling parameters for providers whose current models
 * reject non-default temperature values.
 */
public class SpringAiModelClient implements ModelClient {
    private final String modelId;
    private final ChatClient chatClient;
    private final boolean includeTemperature;
    private final boolean useOpenAiMaxCompletionTokens;

    public SpringAiModelClient(String modelId, ChatClient chatClient) {
        this(modelId, chatClient, true, false);
    }

    public SpringAiModelClient(String modelId, ChatClient chatClient, boolean includeTemperature) {
        this(modelId, chatClient, includeTemperature, false);
    }

    /**
     * @param useOpenAiMaxCompletionTokens emit OpenAI's GPT-5-compatible
     * {@code max_completion_tokens} field instead of the legacy
     * {@code max_tokens} field
     */
    public SpringAiModelClient(String modelId, ChatClient chatClient, boolean includeTemperature,
                               boolean useOpenAiMaxCompletionTokens) {
        this.modelId = modelId;
        this.chatClient = chatClient;
        this.includeTemperature = includeTemperature;
        this.useOpenAiMaxCompletionTokens = useOpenAiMaxCompletionTokens;
    }

    @Override
    public ModelCallResult call(ModelCallRequest request) throws ModelCallException {
        Instant start = Instant.now();
        try {
            String system = request.messages().stream()
                    .filter(message -> "system".equalsIgnoreCase(message.role()))
                    .map(ChatMessage::content)
                    .collect(Collectors.joining("\n\n"));
            String user = request.messages().stream()
                    .filter(message -> !"system".equalsIgnoreCase(message.role()))
                    .map(message -> message.role().toUpperCase() + ":\n" + message.content())
                    .collect(Collectors.joining("\n\n"));

            ChatClient.ChatClientRequestSpec spec = chatClient.prompt();
            if (!system.isBlank()) {
                spec = spec.system(system);
            }
            spec = spec.options(buildOptions(request));
            // Spring AI's generic ChatOptions does not carry a portable per-call
            // timeout. Enforce the ModelProfile timeout at this adapter boundary
            // so cloud calls honour the same contract as direct Ollama calls.
            var requestSpec = spec.user(user);
            // ChatClient#call() only builds a response spec; the provider call
            // is triggered by content()/chatResponse(). Keep the terminal
            // operations inside the timed task or the timeout is illusory.
            // A CallResponseSpec terminal operation performs the provider call.
            // Calling content() and then chatResponse() therefore sends the same
            // logical request twice on Spring AI providers. Fetch the structured
            // response once and derive both content and usage from that object.
            FutureTask<ChatResponse> call = new FutureTask<>(
                    () -> requestSpec.call().chatResponse());
            Thread.startVirtualThread(call);
            ChatResponse chatResponse;
            try {
                Duration timeout = request.timeout();
                if (timeout == null) {
                    chatResponse = call.get();
                } else {
                    chatResponse = call.get(Math.max(1L, timeout.toMillis()), TimeUnit.MILLISECONDS);
                }
            } catch (TimeoutException ex) {
                call.cancel(true);
                throw new TimeoutException(
                        "Model call exceeded timeout " + request.timeout() + " for " + modelId);
            } catch (ExecutionException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof Exception exception) {
                    throw exception;
                }
                throw ex;
            }
            String response = extractVisibleText(chatResponse);
            if (response == null || response.isBlank()) {
                throw new ModelCallException(
                        ModelFailureCategory.INVALID_MODEL_OUTPUT,
                        null,
                        request.providerModelId(),
                        "Model '" + modelId + "' returned an empty response");
            }

            // Extract token usage from Spring AI metadata if available.
            // Token tracking is best-effort; not all providers report usage.
            // Spring AI's Usage interface returns Integer; we widen to Long
            // for consistency with ModelCallResult's nullable Long fields.
            Long promptTokens = null;
            Long completionTokens = null;
            try {
                var result = chatResponse;
                if (result != null && result.getMetadata() != null
                        && result.getMetadata().getUsage() != null) {
                    var usage = result.getMetadata().getUsage();
                    Integer pt = usage.getPromptTokens();
                    Integer ct = usage.getCompletionTokens();
                    promptTokens = pt != null ? pt.longValue() : null;
                    completionTokens = ct != null ? ct.longValue() : null;
                }
            } catch (Exception ignored) {
                // Token tracking is best-effort; swallow failures silently.
            }
            return new ModelCallResult(response == null ? "" : response,
                                       promptTokens, completionTokens,
                                       Duration.between(start, Instant.now()));
        } catch (Exception ex) {
            if (ex instanceof ModelCallException failure) {
                throw failure;
            }
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ModelCallException(
                    category(ex),
                    null,
                    request.providerModelId(),
                    "Model call failed for " + modelId
                    + " using provider model '" + request.providerModelId() + "': "
                    + rootCauseMessage(ex),
                    ex);
        }
    }

    private ChatOptions buildOptions(ModelCallRequest request) {
        if (useOpenAiMaxCompletionTokens) {
            OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                    .model(request.providerModelId())
                    .maxCompletionTokens(request.maxOutputTokens());
            if (includeTemperature) {
                options.temperature(request.temperature());
            }
            return options.build();
        }

        ChatOptions.Builder options = ChatOptions.builder()
                .model(request.providerModelId())
                .maxTokens(request.maxOutputTokens());
        if (includeTemperature) {
            options.temperature(request.temperature());
        }
        return options.build();
    }

    /**
     * Returns only user-visible text from a provider response.
     *
     * <p>Anthropic maps reasoning blocks and text blocks into separate Spring AI
     * generations. {@link ChatResponse#getResult()} returns only the first
     * generation, which can be a reasoning block (or redacted reasoning) rather
     * than the answer. Reasoning metadata must never become council evidence or
     * a user-facing answer, so skip it and retain the text generations.
     */
    private String extractVisibleText(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getResults() == null) {
            return "";
        }
        return chatResponse.getResults().stream()
                .map(generation -> generation == null ? null : generation.getOutput())
                .filter(message -> message != null
                        && !message.getMetadata().containsKey("signature")
                        && !message.getMetadata().containsKey("data"))
                .map(message -> message.getText())
                .filter(text -> text != null && !text.isBlank())
                .collect(Collectors.joining("\n"));
    }

    private String rootCauseMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return current.getClass().getSimpleName()
               + (message == null || message.isBlank() ? "" : " - " + message);
    }

    private ModelFailureCategory category(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof TimeoutException
                || current.getClass().getSimpleName().toLowerCase().contains("timeout")) {
                return ModelFailureCategory.MODEL_TIMEOUT;
            }
            if (current instanceof TransientAiException) {
                return ModelFailureCategory.PROVIDER_UNAVAILABLE;
            }
            current = current.getCause();
        }
        return ModelFailureCategory.MODEL_CALL_FAILED;
    }
}
