package com.debopam.llmcouncil.model;

/**
 * Debate persona assigned to a council member, independent of their structural
 * {@link ModelRole} (MEMBER/CHAIR/VALIDATOR).
 *
 * <p><b>Gap 1.1 (Adversarial Roles):</b> research (DeliberationBench 2025)
 * shows that structurally assigning at least one model the {@link #CRITIC}
 * persona reduces groupthink errors by 15–30%. The persona determines how
 * the model's prompts are framed during GENERATE and DEBATE stages.
 *
 * <ul>
 *   <li>{@link #PROPOSER} — default; produces an independent best answer.</li>
 *   <li>{@link #CRITIC} — devil's advocate; challenges the emerging consensus,
 *       looks for weaknesses, missing assumptions, and edge cases.</li>
 *   <li>{@link #SYNTHESIZER} — bridge-builder; seeks common ground across
 *       diverse perspectives and reconciles conflicting positions.</li>
 * </ul>
 */
public enum CouncilRole {

    /**
     * Default persona. The model produces its own independent answer without
     * any structural bias toward agreement or disagreement.
     */
    PROPOSER,

    /**
     * Devil's advocate. The model is explicitly prompted to challenge the
     * majority view, surface weaknesses, and argue minority positions.
     * At least one CRITIC per council is recommended for robust deliberation.
     */
    CRITIC,

    /**
     * Bridge-builder. The model is prompted to find common ground, reconcile
     * conflicting viewpoints, and produce integrative positions. Useful in
     * councils with high expected disagreement.
     */
    SYNTHESIZER
}
