package com.debopam.llmcouncil.orchestration;

import java.util.List;

/**
 * Captures all contributions from a single debate round.
 *
 * <p>Contributions with {@code confidence == -1} indicate that confidence
 * could not be parsed from the model output and are excluded from
 * convergence scoring.
 *
 * @param roundNumber   Zero-based round index.
 * @param contributions One contribution per council member.
 */
public record DebateRound(int roundNumber, List<DebateContribution> contributions) {

    /**
     * Returns parseable confidence scores only. Contributions where confidence
     * could not be extracted (marked as -1) are excluded to avoid polluting
     * the KS convergence detection with default/synthetic values.
     *
     * @return list of confidence values in [0, 100] from contributions that
     *         successfully reported a confidence score.
     */
    public List<Double> confidenceScores() {
        return contributions.stream()
                            .filter(c -> c.confidence() >= 0)  // Exclude unparseable (-1) values
                            .map(c -> (double) c.confidence())
                            .toList();
    }

    /**
     * How many contributions in this round reported no readable confidence.
     *
     * <p>Excluding these from {@link #confidenceScores()} keeps the convergence
     * and sycophancy maths honest, but silently narrowing the sample is its own
     * misreport: a round where four of five members were unreadable converges
     * trivially. The count exists so the run can say so.
     *
     * @return the number of contributions carrying the {@code -1} sentinel
     */
    public int unreadableConfidenceCount() {
        return (int) contributions.stream().filter(c -> c.confidence() < 0).count();
    }
}
