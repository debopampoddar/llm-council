package com.debopam.llmcouncil.model;

import com.debopam.llmcouncil.orchestration.StageType;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OllamaDirectModelClientTest {

    @Test
    void callsOllamaChatEndpointWithProviderModelAndParsesResponse() throws Exception {
        CapturingHandler handler = new CapturingHandler();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/chat", handler::handle);
        server.start();
        try {
            String baseUrl = "http://localhost:" + server.getAddress().getPort();
            OllamaDirectModelClient client = new OllamaDirectModelClient(
                    "local-llama3", baseUrl, 4096, 4, "10m");

            ModelCallResult result = client.call(new ModelCallRequest(
                    "session-1",
                    StageType.GENERATE,
                    "local-llama3",
                    "llama3.1:8b",
                    List.of(ChatMessage.system("Be terse."), ChatMessage.user("Say hello.")),
                    256,
                    0.2,
                    false,
                    Duration.ofSeconds(5)));

            assertEquals("hello from ollama", result.text());
            assertTrue(handler.body.contains("\"model\":\"llama3.1:8b\""));
            assertTrue(handler.body.contains("\"stream\":true"));
            assertTrue(handler.body.contains("\"keep_alive\":\"10m\""));
            assertTrue(handler.body.contains("\"num_predict\":256"));
            assertTrue(handler.body.contains("\"num_ctx\":4096"));
            assertTrue(handler.body.contains("\"num_thread\":4"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void includesStatusAndBodyWhenOllamaReturnsError() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/chat", exchange -> {
            byte[] response = "{\"error\":\"model not found\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            String baseUrl = "http://localhost:" + server.getAddress().getPort();
            OllamaDirectModelClient client = new OllamaDirectModelClient("local-llama3", baseUrl);

            try {
                client.call(new ModelCallRequest(
                        "session-1",
                        StageType.GENERATE,
                        "local-llama3",
                        "llama3.1:8b",
                        List.of(ChatMessage.user("test")),
                        256,
                        0.2,
                        false,
                        Duration.ofSeconds(5)));
            } catch (ModelCallException ex) {
                assertEquals(ModelFailureCategory.MODEL_NOT_FOUND, ex.category());
                assertTrue(ex.getMessage().contains("Ollama HTTP 404"));
                assertTrue(ex.getMessage().contains("model not found"));
                return;
            }
        } finally {
            server.stop(0);
        }
        throw new AssertionError("Expected ModelCallException");
    }

    @Test
    void categorizesMalformedStreamingChunkAsInvalidModelOutput() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/chat", exchange -> {
            byte[] response = """
                    {"message":{"role":"assistant","content":"hello"},"done":false}
                    not-json
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            OllamaDirectModelClient client = new OllamaDirectModelClient(
                    "local-llama3",
                    "http://localhost:" + server.getAddress().getPort());

            ModelCallException ex = assertThrows(ModelCallException.class, () ->
                    client.call(new ModelCallRequest(
                            "session-1",
                            StageType.GENERATE,
                            "local-llama3",
                            "llama3.1:8b",
                            List.of(ChatMessage.user("test")),
                            256,
                            0.2,
                            false,
                            Duration.ofSeconds(5))));

            assertEquals(ModelFailureCategory.INVALID_MODEL_OUTPUT, ex.category());
            assertTrue(ex.getMessage().contains("invalid JSON"));
        } finally {
            server.stop(0);
        }
    }

    private static class CapturingHandler {
        private String body = "";

        private void handle(HttpExchange exchange) throws IOException {
            body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] response = """
                    {"message":{"role":"assistant","content":"hello "},"done":false}
                    {"message":{"role":"assistant","content":"from ollama"},"done":true}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        }
    }
}
