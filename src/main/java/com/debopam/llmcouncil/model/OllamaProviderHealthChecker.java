package com.debopam.llmcouncil.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Health checker for a configured Ollama runtime.
 *
 * <p>It validates both provider reachability and model tag availability through
 * Ollama's lightweight {@code /api/tags} endpoint.
 */
@Component
public class OllamaProviderHealthChecker implements ProviderHealthChecker {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final URI tagsUri;
    private final Duration timeout;

    public OllamaProviderHealthChecker(
            @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String baseUrl,
            @Value("${council.health.provider-timeout-seconds:5}") long timeoutSeconds) {
        this.tagsUri = tagsUri(baseUrl);
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    @Override
    public boolean supports(String provider) {
        return "ollama".equalsIgnoreCase(provider);
    }

    @Override
    public ProviderHealth check(ModelProfile modelProfile) {
        try {
            String body = get(tagsUri);
            List<String> modelNames = parseModelNames(body);
            if (!modelNames.contains(modelProfile.providerModelId())) {
                return new ProviderHealth(
                        false,
                        ModelFailureCategory.MODEL_NOT_FOUND.name(),
                        "Ollama is reachable at " + tagsUri
                        + " but model '" + modelProfile.providerModelId() + "' is not installed",
                        modelNames);
            }
            return ProviderHealth.available(modelNames);
        } catch (SocketTimeoutException ex) {
            return ProviderHealth.unavailable(ModelFailureCategory.MODEL_TIMEOUT.name(),
                                              "Timed out checking " + tagsUri + ": " + ex.getMessage());
        } catch (Exception ex) {
            return ProviderHealth.unavailable(ModelFailureCategory.PROVIDER_UNAVAILABLE.name(),
                                              ex.getClass().getSimpleName() + ": " + nullSafeMessage(ex));
        }
    }

    private String get(URI uri) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection(Proxy.NO_PROXY);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");
        connection.setConnectTimeout(timeoutMillis(timeout));
        connection.setReadTimeout(timeoutMillis(timeout));
        try {
            int statusCode = connection.getResponseCode();
            String body = responseBody(connection, statusCode);
            if (statusCode < 200 || statusCode >= 300) {
                throw new IllegalStateException("HTTP " + statusCode + " from " + uri + ": " + body);
            }
            return body;
        } finally {
            connection.disconnect();
        }
    }

    private List<String> parseModelNames(String body) throws Exception {
        JsonNode root = OBJECT_MAPPER.readTree(body);
        List<String> names = new ArrayList<>();
        for (JsonNode model : root.path("models")) {
            String name = model.path("name").asText("");
            if (!name.isBlank()) {
                names.add(name);
            }
        }
        return names;
    }

    private URI tagsUri(String baseUrl) {
        String normalized = baseUrl == null || baseUrl.isBlank()
                ? "http://localhost:11434"
                : baseUrl.strip();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return URI.create(normalized.endsWith("/api") ? normalized + "/tags" : normalized + "/api/tags");
    }

    private int timeoutMillis(Duration duration) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, duration.toMillis()));
    }

    private String responseBody(HttpURLConnection connection, int statusCode) throws Exception {
        InputStream inputStream = statusCode >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (inputStream == null) {
            return "";
        }
        try (inputStream) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String nullSafeMessage(Exception ex) {
        return ex.getMessage() == null ? "" : ex.getMessage();
    }
}
