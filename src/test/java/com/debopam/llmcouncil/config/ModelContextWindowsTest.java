package com.debopam.llmcouncil.config;

import com.debopam.llmcouncil.model.ModelRole;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins how a model's effective context window is resolved.
 *
 * <p>Both {@link CouncilConfig} and {@link CouncilConfigurationValidator} depend
 * on this producing the same answer. When the validator read the raw configured
 * value instead, it silently skipped every model that relies on the provider
 * default — which is all of the local ones, the models the check exists for.
 * The bug was invisible: the validator simply stopped warning.
 */
class ModelContextWindowsTest {

    @Test
    void explicitConfigurationWins() {
        assertEquals(32_000, ModelContextWindows.resolve(model("ollama", 32_000), 4096));
    }

    @Test
    void ollamaDerivesFromTheRuntimeWindowNotTheModelArchitecture() {
        // llama3.1 supports 128k, but Ollama serves num-ctx regardless. Budgeting
        // against the architecture figure would overflow the runtime.
        assertEquals(16_384, ModelContextWindows.resolve(model("ollama", 0), 16_384));
    }

    @Test
    void ollamaFallsBackWhenNumCtxIsUnknown() {
        assertEquals(ModelContextWindows.DEFAULT_LOCAL_CONTEXT_TOKENS,
                     ModelContextWindows.resolve(model("ollama", 0), null));
        assertEquals(ModelContextWindows.DEFAULT_LOCAL_CONTEXT_TOKENS,
                     ModelContextWindows.resolve(model("ollama", 0), 0));
    }

    @Test
    void cloudProvidersAssumeALargeWindow() {
        for (String provider : new String[]{"openai", "anthropic", "gemini", "openai-compatible"}) {
            assertEquals(ModelContextWindows.DEFAULT_CLOUD_CONTEXT_TOKENS,
                         ModelContextWindows.resolve(model(provider, 0), 4096),
                         "provider " + provider);
        }
    }

    @Test
    void mockAndUnknownProvidersDisableBudgeting() {
        assertEquals(0, ModelContextWindows.resolve(model("mock", 0), 4096));
        assertEquals(0, ModelContextWindows.resolve(model(null, 0), 4096));
    }

    private CouncilProperties.ModelProps model(String provider, int contextWindowTokens) {
        CouncilProperties.ModelProps model = new CouncilProperties.ModelProps();
        model.setId("m");
        model.setProvider(provider);
        model.setProviderModelId("pm");
        model.setDefaultOutputTokens(1000);
        model.setTimeoutSeconds(60);
        model.setRole(ModelRole.CHAIR);
        model.setContextWindowTokens(contextWindowTokens);
        return model;
    }
}
