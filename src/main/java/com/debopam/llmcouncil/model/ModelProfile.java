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
        String modelFamily
) {
    /**
     * Backwards-compatible constructor for existing callers that don't specify
     * councilRole or modelFamily. Defaults to PROPOSER and null family.
     */
    public ModelProfile(String id, String provider, String providerModelId,
                        int defaultOutputTokens, double temperature,
                        Duration defaultTimeout, ModelRole role) {
        this(id, provider, providerModelId, defaultOutputTokens, temperature,
             defaultTimeout, role, CouncilRole.PROPOSER, null);
    }
}
