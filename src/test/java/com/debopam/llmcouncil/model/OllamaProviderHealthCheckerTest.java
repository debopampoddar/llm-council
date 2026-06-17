package com.debopam.llmcouncil.model;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OllamaProviderHealthCheckerTest {

    @Test
    void reportsAvailableWhenConfiguredModelIsInstalled() throws Exception {
        HttpServer server = serverReturning("""
                {"models":[{"name":"llama3.1:8b"},{"name":"mistral:7b"}]}
                """, 200);
        server.start();
        try {
            OllamaProviderHealthChecker checker = new OllamaProviderHealthChecker(baseUrl(server), 5);
            ModelProfile model = model("llama3.1:8b");

            ProviderHealth health = checker.check(model);

            assertTrue(health.available());
            assertEquals("AVAILABLE", health.status());
            assertTrue(health.knownProviderModels().contains("llama3.1:8b"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reportsModelNotFoundWhenOllamaIsReachableButTagIsMissing() throws Exception {
        HttpServer server = serverReturning("""
                {"models":[{"name":"mistral:7b"}]}
                """, 200);
        server.start();
        try {
            OllamaProviderHealthChecker checker = new OllamaProviderHealthChecker(baseUrl(server), 5);

            ProviderHealth health = checker.check(model("llama3.1:8b"));

            assertFalse(health.available());
            assertEquals(ModelFailureCategory.MODEL_NOT_FOUND.name(), health.status());
            assertTrue(health.detail().contains("llama3.1:8b"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reportsProviderUnavailableWhenOllamaCannotBeReached() throws Exception {
        HttpServer server = serverReturning("{}", 200);
        server.start();
        int port = server.getAddress().getPort();
        server.stop(0);

        OllamaProviderHealthChecker checker = new OllamaProviderHealthChecker("http://localhost:" + port, 1);

        ProviderHealth health = checker.check(model("llama3.1:8b"));

        assertFalse(health.available());
        assertEquals(ModelFailureCategory.PROVIDER_UNAVAILABLE.name(), health.status());
    }

    private HttpServer serverReturning(String body, int status) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/tags", exchange -> {
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        return server;
    }

    private String baseUrl(HttpServer server) {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private ModelProfile model(String providerModelId) {
        return new ModelProfile("local-llama3", "ollama", providerModelId,
                                1000, 0.2, Duration.ofSeconds(30), ModelRole.MEMBER);
    }
}
