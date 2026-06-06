/**
 * Auto-generated documentation for OllamaModelClient.java.
 * Part of the llm-council Java implementation of multi-LLM deliberation.
 */

package com.debopam.llmcouncil.model;

import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public class OllamaModelClient implements CouncilModelClient {
    private final ProviderProfile provider;
    private final WebClient webClient;

    public OllamaModelClient(ProviderProfile provider, WebClient.Builder builder) {
        this.provider = provider;
        this.webClient = builder.baseUrl(provider.baseUrl().toString()).build();
    }

    @Override
    public ModelCallResult call(ModelCallRequest request) {
        Instant started = Instant.now();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", request.providerModelId());
        payload.put("messages", request.messages().stream()
                .map(message -> Map.of("role", message.role(), "content", message.content()))
                .toList());
        payload.put("stream", false);
        payload.put("options", Map.of(
                "temperature", request.temperature() == null ? 0.2 : request.temperature(),
                "num_predict", request.maxOutputTokens() == null ? 1200 : request.maxOutputTokens()
        ));
        if (request.requestJson()) {
            payload.put("format", "json");
        }

        Map<?, ?> raw = webClient.post()
                .uri("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(Map.class)
                .block(request.timeout() == null ? provider.timeout() : request.timeout());

        if (raw == null) {
            throw new ModelCallException(request.logicalModelId(), provider.id(), "Ollama returned no response", null);
        }

        return new ModelCallResult(
                request.logicalModelId(),
                provider.id(),
                extractContent(raw),
                0,
                0,
                Duration.between(started, Instant.now()),
                castMap(raw)
        );
    }

    @Override
    public ModelHealth health() {
        return ModelHealth.available(provider.id());
    }

    private String extractContent(Map<?, ?> raw) {
        Object messageObj = raw.get("message");
        if (messageObj instanceof Map<?, ?> messageMap) {
            Object content = messageMap.get("content");
            return content == null ? "" : content.toString();
        }
        Object response = raw.get("response");
        return response == null ? "" : response.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Map<?, ?> raw) {
        return (Map<String, Object>) raw;
    }
}
