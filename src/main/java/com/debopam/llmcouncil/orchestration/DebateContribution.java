package com.debopam.llmcouncil.orchestration;

/**
 * One model's argument in a single debate round.
 *
 * @param modelId    The model making this contribution.
 * @param text       The argument text.
 * @param confidence Numeric confidence in the model's current position (0–100).
 *                   Parsed from a {@code Confidence: NN} line in the model's output.
 */
public record DebateContribution(String modelId, String text, int confidence) {}
