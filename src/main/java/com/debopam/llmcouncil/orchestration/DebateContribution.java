package com.debopam.llmcouncil.orchestration;

/**
 * A single model's contribution to a debate round.
 *
 * @param modelId    The contributing model's ID.
 * @param text       The full debate argument text.
 * @param confidence Parsed confidence score (0–100), or {@code -1} if confidence
 *                   could not be extracted from the model's output. The sentinel
 *                   value allows {@link DebateRound#confidenceScores()} to exclude
 *                   unparseable entries from the KS convergence calculation rather
 *                   than injecting a misleading default (Gap 4.4).
 */
public record DebateContribution(String modelId, String text, int confidence) {}
