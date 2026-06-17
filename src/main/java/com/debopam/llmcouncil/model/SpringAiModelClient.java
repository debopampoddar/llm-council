package com.debopam.llmcouncil.model;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Spring AI-backed model client.
 *
 * <p>The project uses this adapter for OpenAI, Anthropic, Ollama, and
 * OpenAI-compatible/OCI-style provider beans. Provider-specific option binding
 * can be added later; this adapter at least preserves system/user separation in
 * the composed prompt and records latency.
 */
public class SpringAiModelClient implements ModelClient {
    private final String modelId;
    private final ChatClient chatClient;

    public SpringAiModelClient(String modelId, ChatClient chatClient) {
        this.modelId = modelId;
        this.chatClient = chatClient;
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
            spec = spec.options(ChatOptions.builder()
                                           .model(request.providerModelId())
                                           .maxTokens(request.maxOutputTokens())
                                           .temperature(request.temperature())
                                           .build());
            String response = spec.user(user).call().content();
            return new ModelCallResult(response == null ? "" : response,
                                       null, null, Duration.between(start, Instant.now()));
        } catch (Exception ex) {
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
        while (current.getCause() != null) {
            current = current.getCause();
        }
        if (current instanceof TimeoutException
            || current.getClass().getSimpleName().toLowerCase().contains("timeout")) {
            return ModelFailureCategory.MODEL_TIMEOUT;
        }
        return ModelFailureCategory.MODEL_CALL_FAILED;
    }
}
