package com.debopam.llmcouncil.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Discovers which models are actually installed in the configured Ollama runtime.
 *
 * <p>Reads Ollama's lightweight {@code /api/tags} endpoint. This was previously
 * inlined in {@link OllamaProviderHealthChecker}, which could only answer
 * "is this one model present?". Exposing the installed list on its own lets the
 * catalog report what is available and lets configuration tooling avoid ever
 * proposing a model the user has not pulled.
 *
 * <p><b>This class never throws.</b> Discovery is advisory: an unreachable or
 * slow Ollama must degrade the answer, never fail the request that asked.
 */
@Component
public class OllamaModelDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(OllamaModelDiscoveryService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final URI tagsUri;
    private final Duration timeout;

    /**
     * @param baseUrl        Ollama base URL, for example {@code http://localhost:11434}
     * @param timeoutSeconds connect and read timeout for the discovery call
     */
    public OllamaModelDiscoveryService(
            @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String baseUrl,
            @Value("${council.health.provider-timeout-seconds:5}") long timeoutSeconds) {
        this.tagsUri = tagsUri(baseUrl);
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    /**
     * List the provider model ids installed in the Ollama runtime.
     *
     * @return installed model names such as {@code llama3.1:8b}, or an empty
     *         list when Ollama is unreachable, slow, or returns an error
     */
    public List<String> installedModels() {
        try {
            return parseModelNames(fetchTags());
        } catch (Exception ex) {
            log.debug("Ollama model discovery failed for {}: {}", tagsUri, ex.toString());
            return List.of();
        }
    }

    /** @return the resolved {@code /api/tags} URI this service queries */
    public URI tagsUri() {
        return tagsUri;
    }

    /**
     * Fetch the raw {@code /api/tags} response body.
     *
     * @return the response body
     * @throws Exception if the runtime is unreachable or returns a non-2xx status
     */
    String fetchTags() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) tagsUri.toURL().openConnection(Proxy.NO_PROXY);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");
        connection.setConnectTimeout(timeoutMillis());
        connection.setReadTimeout(timeoutMillis());
        try {
            int statusCode = connection.getResponseCode();
            String body = responseBody(connection, statusCode);
            if (statusCode < 200 || statusCode >= 300) {
                throw new IllegalStateException("HTTP " + statusCode + " from " + tagsUri + ": " + body);
            }
            return body;
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Parse installed model names out of an {@code /api/tags} response body.
     *
     * @param body the raw JSON response
     * @return model names, never null
     * @throws Exception if the body is not valid JSON
     */
    static List<String> parseModelNames(String body) throws Exception {
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

    /**
     * Resolve the {@code /api/tags} URI from a configured base URL.
     *
     * @param baseUrl the Ollama base URL; blank falls back to localhost
     * @return the tags endpoint URI
     */
    static URI tagsUri(String baseUrl) {
        String normalized = baseUrl == null || baseUrl.isBlank()
                ? "http://localhost:11434"
                : baseUrl.strip();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return URI.create(normalized.endsWith("/api") ? normalized + "/tags" : normalized + "/api/tags");
    }

    private int timeoutMillis() {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, timeout.toMillis()));
    }

    private static String responseBody(HttpURLConnection connection, int statusCode) throws Exception {
        InputStream inputStream = statusCode >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (inputStream == null) {
            return "";
        }
        try (inputStream) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
