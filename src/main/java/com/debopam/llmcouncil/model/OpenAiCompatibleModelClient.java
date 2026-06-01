package com.debopam.llmcouncil.model;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OpenAiCompatibleModelClient implements CouncilModelClient {
    private final ProviderProfile provider;
    private final WebClient webClient;

    public OpenAiCompatibleModelClient(ProviderProfile provider, WebClient.Builder builder) {
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
        payload.put("temperature", request.temperature() == null ? 0.2 : request.temperature());
        payload.put("max_tokens", request.maxOutputTokens() == null ? 1200 : request.maxOutputTokens());
        if (request.requestJson()) {
            payload.put("response_format", Map.of("type", "json_object"));
        }

        WebClient.RequestHeadersSpec<?> spec = webClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload);

        String apiKey = provider.resolveApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }

        Map<?, ?> raw = spec.retrieve()
                .bodyToMono(Map.class)
                .block(request.timeout() == null ? provider.timeout() : request.timeout());

        if (raw == null) {
            throw new ModelCallException(request.logicalModelId(), provider.id(), "Provider returned no response", null);
        }
        Map<String, Object> usage = raw.get("usage") instanceof Map<?, ?> usageMap
                ? castMap(usageMap)
                : Map.of();

        return new ModelCallResult(
                request.logicalModelId(),
                provider.id(),
                extractContent(raw),
                numberValue(usage.get("prompt_tokens")),
                numberValue(usage.get("completion_tokens")),
                Duration.between(started, Instant.now()),
                castMap(raw)
        );
    }

    @Override
    public ModelHealth health() {
        return ModelHealth.available(provider.id());
    }

    private String extractContent(Map<?, ?> raw) {
        Object choicesObj = raw.get("choices");
        if (!(choicesObj instanceof List<?> choices) || choices.isEmpty()) {
            throw new IllegalStateException("Model response does not contain choices");
        }
        Object firstChoice = choices.get(0);
        if (!(firstChoice instanceof Map<?, ?> choiceMap)) {
            throw new IllegalStateException("Model response first choice is not an object");
        }
        Object messageObj = choiceMap.get("message");
        if (!(messageObj instanceof Map<?, ?> messageMap)) {
            throw new IllegalStateException("Model response choice does not contain a message object");
        }
        Object content = messageMap.get("content");
        return content == null ? "" : content.toString();
    }

    private long numberValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Map<?, ?> raw) {
        return (Map<String, Object>) raw;
    }
}
