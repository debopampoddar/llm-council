package com.debopam.llmcouncil.config;

/**
 * Resolves a model's effective context window.
 *
 * <p>Shared by {@link CouncilConfig}, which stamps the window onto each
 * {@code ModelProfile}, and {@link CouncilConfigurationValidator}, which warns
 * when a chair cannot hold the evidence its council will produce. Both must
 * agree: if the validator used the raw configured value it would skip every
 * model relying on the provider default, which is most of them.
 */
final class ModelContextWindows {

    /** Fallback local window when Ollama's num-ctx is not configured. */
    static final int DEFAULT_LOCAL_CONTEXT_TOKENS = 4096;

    /** Conservative default for cloud providers, which are all far larger. */
    static final int DEFAULT_CLOUD_CONTEXT_TOKENS = 128_000;

    private ModelContextWindows() {
    }

    /**
     * Resolve the context window a model will actually be budgeted against.
     *
     * <p>For Ollama the figure that matters is the runtime's {@code num-ctx},
     * not the model architecture's theoretical window: Ollama serves the
     * configured window regardless of what the model could support, so
     * budgeting against the larger number would overflow and lose content.
     *
     * @param mp            the model configuration
     * @param ollamaNumCtx  the configured Ollama context size, may be null
     * @return context window in tokens, or 0 when unknown (budgeting disabled)
     */
    static int resolve(CouncilProperties.ModelProps mp, Integer ollamaNumCtx) {
        if (mp.getContextWindowTokens() > 0) {
            return mp.getContextWindowTokens();
        }
        if (mp.getProvider() == null) {
            return 0;
        }
        return switch (mp.getProvider().toLowerCase()) {
            case "ollama" -> ollamaNumCtx != null && ollamaNumCtx > 0
                             ? ollamaNumCtx
                             : DEFAULT_LOCAL_CONTEXT_TOKENS;
            case "mock" -> 0;
            default -> DEFAULT_CLOUD_CONTEXT_TOKENS;
        };
    }
}
