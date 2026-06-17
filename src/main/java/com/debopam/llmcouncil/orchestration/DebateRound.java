package com.debopam.llmcouncil.orchestration;

import java.util.List;

/**
 * All contributions from one round of multi-agent debate.
 *
 * @param roundNumber   Zero-based round index.
 * @param contributions One contribution per council member.
 */
public record DebateRound(int roundNumber, List<DebateContribution> contributions) {

    /** Extract confidence scores from all contributions in this round. */
    public List<Double> confidenceScores() {
        return contributions.stream()
                            .map(c -> (double) c.confidence())
                            .toList();
    }
}
