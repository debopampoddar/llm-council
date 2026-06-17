package com.debopam.llmcouncil.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Direct Ollama HTTP adapter for local models.
 *
 * <p>Spring AI remains useful for cloud provider abstractions, but local Docker
 * setups often fail with lower-level channel errors before the Ollama response
 * is visible. This adapter calls Ollama's stable {@code /api/chat} endpoint
 * directly, consumes Ollama's newline-delimited streaming response, and returns
 * HTTP status/body details when the model server rejects the request.
 */
public class OllamaDirectModelClient implements ModelClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaDirectModelClient.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(4);
    private static final int MAX_ERROR_BODY_CHARS = 1_000;

    private final String modelId;
    private final URI chatUri;
    private final Integer numCtx;
    private final Integer numThread;
    private final String keepAlive;

    public OllamaDirectModelClient(String modelId, String baseUrl) {
        this(modelId, baseUrl, null, null, null);
    }

    public OllamaDirectModelClient(String modelId, String baseUrl,
                                   Integer numCtx, Integer numThread, String keepAlive) {
        this.modelId = modelId;
        this.chatUri = chatUri(baseUrl);
        this.numCtx = numCtx;
        this.numThread = numThread;
        this.keepAlive = keepAlive;
        log.info("Ollama client {} configured for {} with direct networking, resolvedAddresses={}, numCtx={}, numThread={}, keepAlive={}, proxy={}",
                 modelId, chatUri, resolvedAddresses(), numCtx, numThread, keepAlive, proxySummary());
    }

    @Override
    public ModelCallResult call(ModelCallRequest request) throws ModelCallException {
        Instant start = Instant.now();
        try {
            log.debug("Calling Ollama modelId={} providerModel={} uri={} timeout={} messages={} jsonMode={}",
                      modelId, request.providerModelId(), chatUri,
                      request.timeout() == null ? DEFAULT_TIMEOUT : request.timeout(),
                      request.messages().size(), request.jsonMode());
            OllamaHttpResponse response = post(OBJECT_MAPPER.writeValueAsString(payload(request)),
                                               request.timeout() == null ? DEFAULT_TIMEOUT : request.timeout());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ModelCallException(
                        categoryForStatus(response.statusCode()),
                        "ollama",
                        request.providerModelId(),
                        "Ollama HTTP " + response.statusCode()
                        + " from " + chatUri
                        + " for provider model '" + request.providerModelId() + "': "
                        + truncate(response.body()));
            }

            String text = parseOllamaResponse(response.body(), request.providerModelId());
            return new ModelCallResult(text, null, null, Duration.between(start, Instant.now()));
        } catch (ModelCallException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("Ollama call failed modelId={} providerModel={} uri={} elapsed={} rootCause={} proxy={}",
                     modelId, request.providerModelId(), chatUri,
                     Duration.between(start, Instant.now()), rootCauseMessage(ex), proxySummary(), ex);
            throw new ModelCallException(
                    categoryForException(ex),
                    "ollama",
                    request.providerModelId(),
                    "Ollama call failed for " + modelId
                    + " at " + chatUri
                    + " using provider model '" + request.providerModelId() + "': "
                    + rootCauseMessage(ex),
                    ex);
        }
    }

    private Map<String, Object> payload(ModelCallRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.providerModelId());
        body.put("stream", true);
        if (request.jsonMode()) {
            body.put("format", "json");
        }
        if (keepAlive != null && !keepAlive.isBlank()) {
            body.put("keep_alive", keepAlive);
        }
        body.put("messages", messages(request.messages()));

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("temperature", request.temperature());
        if (request.maxOutputTokens() > 0) {
            options.put("num_predict", request.maxOutputTokens());
        }
        if (numCtx != null && numCtx > 0) {
            options.put("num_ctx", numCtx);
        }
        if (numThread != null && numThread > 0) {
            options.put("num_thread", numThread);
        }
        body.put("options", options);
        return body;
    }

    private List<Map<String, String>> messages(List<ChatMessage> messages) {
        return messages.stream()
                .map(message -> Map.of("role", normalizeRole(message.role()),
                                       "content", message.content()))
                .toList();
    }

    private String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return "user";
        }
        String normalized = role.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "system", "assistant", "user" -> normalized;
            default -> "user";
        };
    }

    private URI chatUri(String baseUrl) {
        String normalized = baseUrl == null || baseUrl.isBlank()
                ? "http://localhost:11434"
                : baseUrl.strip();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return URI.create(normalized.endsWith("/api") ? normalized + "/chat" : normalized + "/api/chat");
    }

    private OllamaHttpResponse post(String body, Duration timeout) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) chatUri.toURL().openConnection(Proxy.NO_PROXY);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json");
        connection.setConnectTimeout((int) Math.min(Integer.MAX_VALUE, Duration.ofSeconds(10).toMillis()));
        connection.setReadTimeout(timeoutMillis(timeout));
        connection.setDoOutput(true);

        byte[] requestBytes = body.getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(requestBytes.length);
        try {
            connection.connect();
            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(requestBytes);
            }

            int statusCode = connection.getResponseCode();
            return new OllamaHttpResponse(statusCode, responseBody(connection, statusCode));
        } finally {
            connection.disconnect();
        }
    }

    private int timeoutMillis(Duration timeout) {
        long millis = Math.max(1, timeout.toMillis());
        return (int) Math.min(Integer.MAX_VALUE, millis);
    }

    private String responseBody(HttpURLConnection connection, int statusCode) throws IOException {
        InputStream inputStream = statusCode >= 400
                ? connection.getErrorStream()
                : connection.getInputStream();
        if (inputStream == null) {
            return "";
        }
        try (inputStream) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String parseOllamaResponse(String body, String providerModelId) {
        if (body == null || body.isBlank()) {
            return "";
        }

        if (!body.contains("\n")) {
            return contentFromJson(body, providerModelId);
        }

        StringBuilder text = new StringBuilder();
        for (String line : body.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode root = readJson(line, providerModelId);
            JsonNode error = root.get("error");
            if (error != null && !error.asText("").isBlank()) {
                throw new ModelCallException(ModelFailureCategory.MODEL_CALL_FAILED,
                                             "ollama",
                                             providerModelId,
                                             "Ollama error from " + chatUri
                                             + " for provider model '" + providerModelId + "': "
                                             + error.asText());
            }
            text.append(root.path("message").path("content").asText(""));
            if (root.has("response")) {
                text.append(root.path("response").asText(""));
            }
        }
        return text.toString();
    }

    private String contentFromJson(String body, String providerModelId) {
        JsonNode root = readJson(body, providerModelId);
        JsonNode error = root.get("error");
        if (error != null && !error.asText("").isBlank()) {
            throw new ModelCallException(ModelFailureCategory.MODEL_CALL_FAILED,
                                         "ollama",
                                         providerModelId,
                                         "Ollama error from " + chatUri
                                         + " for provider model '" + providerModelId + "': "
                                         + error.asText());
        }

        String text = root.path("message").path("content").asText("");
        if (text.isBlank()) {
            text = root.path("response").asText("");
        }
        return text;
    }

    private JsonNode readJson(String body, String providerModelId) {
        try {
            return OBJECT_MAPPER.readTree(body);
        } catch (Exception ex) {
            throw new ModelCallException(ModelFailureCategory.INVALID_MODEL_OUTPUT,
                                         "ollama",
                                         providerModelId,
                                         "Ollama returned invalid JSON from " + chatUri
                                         + " for provider model '" + providerModelId + "': "
                                         + truncate(body),
                                         ex);
        }
    }

    private String truncate(String value) {
        if (value == null || value.isBlank()) {
            return "<empty response body>";
        }
        return value.length() <= MAX_ERROR_BODY_CHARS
                ? value
                : value.substring(0, MAX_ERROR_BODY_CHARS) + "...";
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

    private ModelFailureCategory categoryForStatus(int statusCode) {
        if (statusCode == 404) {
            return ModelFailureCategory.MODEL_NOT_FOUND;
        }
        if (statusCode == 408 || statusCode == 504) {
            return ModelFailureCategory.MODEL_TIMEOUT;
        }
        if (statusCode >= 500) {
            return ModelFailureCategory.PROVIDER_UNAVAILABLE;
        }
        return ModelFailureCategory.MODEL_CALL_FAILED;
    }

    private ModelFailureCategory categoryForException(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        if (current instanceof SocketTimeoutException) {
            return ModelFailureCategory.MODEL_TIMEOUT;
        }
        if (current instanceof ConnectException) {
            return ModelFailureCategory.PROVIDER_UNAVAILABLE;
        }
        if (current instanceof IOException) {
            return ModelFailureCategory.PROVIDER_UNAVAILABLE;
        }
        return ModelFailureCategory.MODEL_CALL_FAILED;
    }

    private String proxySummary() {
        return "http.proxyHost=" + safeProperty("http.proxyHost")
               + ", http.proxyPort=" + safeProperty("http.proxyPort")
               + ", http.nonProxyHosts=" + safeProperty("http.nonProxyHosts")
               + ", https.proxyHost=" + safeProperty("https.proxyHost")
               + ", https.proxyPort=" + safeProperty("https.proxyPort");
    }

    private String safeProperty(String name) {
        String value = System.getProperty(name);
        return value == null || value.isBlank() ? "<unset>" : value;
    }

    private String resolvedAddresses() {
        try {
            return Arrays.stream(InetAddress.getAllByName(chatUri.getHost()))
                    .map(InetAddress::getHostAddress)
                    .collect(Collectors.joining(","));
        } catch (Exception ex) {
            return "<unresolved:" + ex.getClass().getSimpleName() + ">";
        }
    }

    private record OllamaHttpResponse(int statusCode, String body) {}
}
