package com.debopam.llmcouncil.config;

import com.debopam.llmcouncil.model.OllamaDirectModelClient;
import com.debopam.llmcouncil.model.UnavailableModelClient;
import com.debopam.llmcouncil.orchestration.ProtocolDefinitionRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class CouncilConfigProbeTest {

    @Test
    void probeNeverUsesGlobalMockFallbackForAnUnconfiguredCloudProvider() {
        CouncilProperties properties = new CouncilProperties();
        properties.setAllowMockFallback(true);
        CouncilConfig config = config(properties);

        assertInstanceOf(UnavailableModelClient.class,
                         config.buildProbeClient("openai", "gpt-4.1-mini"));
        assertInstanceOf(UnavailableModelClient.class,
                         config.buildProbeClient("anthropic", "claude-sonnet-4-20250514"));
        assertInstanceOf(UnavailableModelClient.class,
                         config.buildProbeClient("gemini", "gemini-2.5-flash"));
    }

    @Test
    void localProbeUsesTheRealDirectAdapter() {
        assertInstanceOf(OllamaDirectModelClient.class,
                         config(new CouncilProperties()).buildProbeClient("ollama", "llama3.1:8b"));
    }

    private CouncilConfig config(CouncilProperties properties) {
        return new CouncilConfig(properties, new ProtocolDefinitionRegistry(),
                new CouncilConfigurationValidator(4096),
                "http://127.0.0.1:11434", 4096, 0, "1m",
                "unused-development-placeholder", "unused-development-placeholder", "");
    }
}
