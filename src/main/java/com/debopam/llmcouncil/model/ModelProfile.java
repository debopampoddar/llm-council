package com.debopam.llmcouncil.model;

import java.time.Duration;

/**
 * Immutable metadata describing a configured model's identity, provider
 * binding, resource limits, and council personas.
 *
 * <p><b>(Adversarial Roles):</b> {@code councilRole} defines the
 * debate persona (PROPOSER / CRITIC / SYNTHESIZER) used to vary prompts
 * during GENERATE and DEBATE stages.
 *
 * <p><b>(Model Heterogeneity):</b> {@code modelFamily} identifies
 * the architecture family (e.g., "llama", "gpt", "claude") for diversity
 * validation at startup.
 *
 * @param id                  Logical model identifier used in policies and events.
 * @param provider            Provider key (e.g., "ollama", "openai", "anthropic").
 * @param providerModelId     Provider-specific model name (e.g., "llama3.1:8b").
 * @param defaultOutputTokens Maximum output tokens for calls to this model.
 * @param temperature         Sampling temperature (0.0–2.0).
 * @param defaultTimeout      Maximum wall-clock time for a single model call.
 * @param role                Structural role in the council (MEMBER/CHAIR/VALIDATOR).
 * @param councilRole         Debate persona (PROPOSER/CRITIC/SYNTHESIZER). Defaults to PROPOSER.
 * @param modelFamily         Architecture family tag for heterogeneity validation (nullable).
 * @param contextWindowTokens Total context window in tokens (prompt plus response).
 *                            Zero or less means unknown, which disables prompt budgeting.
 * @param costPer1kInputTokens  Price in USD per 1,000 prompt tokens. Zero means
 *                              <b>unpriced</b>, not free — see {@link #priced()}.
 * @param costPer1kOutputTokens Price in USD per 1,000 completion tokens. Zero
 *                              means unpriced, not free.
 */
public record ModelProfile(
        String id,
        String provider,
        String providerModelId,
        int defaultOutputTokens,
        double temperature,
        Duration defaultTimeout,
        ModelRole role,
        CouncilRole councilRole,
        String modelFamily,
        int contextWindowTokens,
        double costPer1kInputTokens,
        double costPer1kOutputTokens
) {
    /**
     * Whether this model carries a price at all.
     *
     * <p>The distinction matters more than it looks. A cloud model left at the
     * default zero has an unknown price, not a zero one, and reporting its cost
     * as {@code $0.00} would make an expensive model indistinguishable from a
     * local one that genuinely costs nothing. Callers use this to render "—"
     * instead of a number.
     *
     * @return {@code true} when either token price is non-zero.
     */
    public boolean priced() {
        return costPer1kInputTokens > 0.0 || costPer1kOutputTokens > 0.0;
    }

    /**
     * Constructor for callers that do not specify token prices.
     *
     * @param id                  Logical model identifier.
     * @param provider            Provider key.
     * @param providerModelId     Provider-specific model name.
     * @param defaultOutputTokens Maximum output tokens for calls to this model.
     * @param temperature         Sampling temperature.
     * @param defaultTimeout      Maximum wall-clock time for a single model call.
     * @param role                Structural role in the council.
     * @param councilRole         Debate persona.
     * @param modelFamily         Architecture family tag.
     * @param contextWindowTokens Total context window in tokens.
     */
    public ModelProfile(String id, String provider, String providerModelId,
                        int defaultOutputTokens, double temperature,
                        Duration defaultTimeout, ModelRole role,
                        CouncilRole councilRole, String modelFamily,
                        int contextWindowTokens) {
        this(id, provider, providerModelId, defaultOutputTokens, temperature,
             defaultTimeout, role, councilRole, modelFamily, contextWindowTokens, 0.0, 0.0);
    }

    /**
     * Constructor for callers that do not specify a context window.
     *
     * @param id                  Logical model identifier.
     * @param provider            Provider key.
     * @param providerModelId     Provider-specific model name.
     * @param defaultOutputTokens Maximum output tokens for calls to this model.
     * @param temperature         Sampling temperature.
     * @param defaultTimeout      Maximum wall-clock time for a single model call.
     * @param role                Structural role in the council.
     * @param councilRole         Debate persona.
     * @param modelFamily         Architecture family tag.
     */
    public ModelProfile(String id, String provider, String providerModelId,
                        int defaultOutputTokens, double temperature,
                        Duration defaultTimeout, ModelRole role,
                        CouncilRole councilRole, String modelFamily) {
        this(id, provider, providerModelId, defaultOutputTokens, temperature,
             defaultTimeout, role, councilRole, modelFamily, 0);
    }

    /**
     * Backwards-compatible constructor for existing callers that don't specify
     * councilRole or modelFamily. Defaults to PROPOSER and null family.
     */
    public ModelProfile(String id, String provider, String providerModelId,
                        int defaultOutputTokens, double temperature,
                        Duration defaultTimeout, ModelRole role) {
        this(id, provider, providerModelId, defaultOutputTokens, temperature,
             defaultTimeout, role, CouncilRole.PROPOSER, null, 0, 0.0, 0.0);
    }
}
