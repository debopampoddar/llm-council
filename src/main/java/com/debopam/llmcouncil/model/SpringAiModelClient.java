package com.debopam.llmcouncil.model;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
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

    public SpringAiModelClient(String modelId, ChatClient chatClient) {
        this(modelId, chatClient, true);
    }

    public SpringAiModelClient(String modelId, ChatClient chatClient, boolean includeTemperature) {
        this.modelId = modelId;
        this.chatClient = chatClient;
        this.includeTemperature = includeTemperature;
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
            ChatOptions.Builder options = ChatOptions.builder()
                                                     .model(request.providerModelId())
                                                     .maxTokens(request.maxOutputTokens());
            if (includeTemperature) {
                options.temperature(request.temperature());
            }
            spec = spec.options(options.build());
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
            String response = chatResponse == null || chatResponse.getResult() == null
                    || chatResponse.getResult().getOutput() == null
                    ? ""
                    : chatResponse.getResult().getOutput().getText();

            // Extract token usage from Spring AI metadata if available.
            // Token tracking is best-effort; not all providers report usage.
            // Spring AI 1.0.0 Usage interface returns Integer; we widen to Long
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
