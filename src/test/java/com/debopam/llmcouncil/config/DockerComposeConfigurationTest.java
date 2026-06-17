package com.debopam.llmcouncil.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerComposeConfigurationTest {

    @Test
    void appOnlyComposeKeepsRancherDefaultAndRuntimeOverride() throws Exception {
        String compose = Files.readString(Path.of("docker-compose.m1-32gb-app-only.yml"));

        assertTrue(compose.contains(
                "SPRING_AI_OLLAMA_BASE_URL: ${SPRING_AI_OLLAMA_BASE_URL:-http://host.rancher-desktop.internal:11434}"));
        assertTrue(compose.contains("SPRING_AI_OLLAMA_BASE_URL=http://host.docker.internal:11434"));
        assertTrue(compose.contains("host.rancher-desktop.internal"));
        assertTrue(compose.contains("host.lima.internal"));
        assertTrue(compose.contains("NO_PROXY"));
    }
}
