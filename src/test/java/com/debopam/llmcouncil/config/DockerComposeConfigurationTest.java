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
        assertTrue(compose.contains("LLM_COUNCIL_LOCAL_THIRD_MODEL"));
    }

    @Test
    void fullComposeStacksPullAndConfigureTheRigorousThirdMember() throws Exception {
        String m1 = Files.readString(Path.of("docker-compose.m1-32gb.yml"));
        String intel = Files.readString(Path.of("docker-compose.intel-2019-32gb.yml"));

        assertTrue(m1.contains("ollama pull \"${LLM_COUNCIL_LOCAL_THIRD_MODEL:-qwen2.5:7b}\""));
        assertTrue(m1.contains("LLM_COUNCIL_LOCAL_THIRD_MODEL: ${LLM_COUNCIL_LOCAL_THIRD_MODEL:-qwen2.5:7b}"));
        assertTrue(intel.contains("ollama pull \"${LLM_COUNCIL_LOCAL_THIRD_MODEL:-qwen2.5:7b}\""));
        assertTrue(intel.contains("LLM_COUNCIL_LOCAL_THIRD_MODEL: ${LLM_COUNCIL_LOCAL_THIRD_MODEL:-qwen2.5:7b}"));
        assertTrue(intel.contains("LLM_COUNCIL_LOCAL_ALT_MODEL_FAMILY: qwen"));
    }
}
