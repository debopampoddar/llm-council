package com.debopam.llmcouncil.orchestration;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ScoringService {
    public ScoreSummary score(List<PeerReviewOutput> reviews, DebateSummary debateSummary) {
        Map<String, List<Integer>> scoresByDraft = new HashMap<>();
        for (PeerReviewOutput review : reviews) {
            for (DraftEvaluation evaluation : review.evaluations()) {
                int average = (int) Math.round(evaluation.scores().stream().mapToInt(CriterionScore::score).average().orElse(0));
                scoresByDraft.computeIfAbsent(evaluation.draftId(), ignored -> new ArrayList<>()).add(average);
            }
        }

        List<DraftScore> draftScores = scoresByDraft.entrySet().stream()
                .map(entry -> {
                    double base = entry.getValue().stream().mapToInt(Integer::intValue).average().orElse(0);
                    double adjustment = debateAdjustment(entry.getKey(), debateSummary);
                    double weighted = clamp(base + adjustment, 0.0, 100.0);
                    return new DraftScore(entry.getKey(), base, adjustment, weighted, entry.getValue().size());
                })
                .sorted(Comparator.comparingDouble(DraftScore::weightedScore).reversed())
                .toList();

        String winner = draftScores.isEmpty() ? null : draftScores.get(0).draftId();
        double variance = variance(draftScores.stream().map(DraftScore::weightedScore).toList());
        return new ScoreSummary(draftScores, variance, winner);
    }

    private double debateAdjustment(String draftId, DebateSummary debateSummary) {
        if (debateSummary == null || debateSummary.skipped()) {
            return 0.0;
        }

        int support = 0;
        int challenge = 0;
        int risk = 0;

        for (DebateRound round : debateSummary.rounds()) {
            for (DebateContribution contribution : round.contributions()) {
                if (contribution.supportedDraftIds() != null && contribution.supportedDraftIds().contains(draftId)) {
                    support++;
                }
                if (contribution.challengedDraftIds() != null && contribution.challengedDraftIds().contains(draftId)) {
                    challenge++;
                }
                if (contribution.challengedDraftIds() != null
                        && contribution.challengedDraftIds().contains(draftId)
                        && contribution.unresolvedRisks() != null
                        && !contribution.unresolvedRisks().isEmpty()) {
                    risk++;
                }
            }
        }

        double positive = Math.min(5.0, support * 1.5);
        double negative = Math.min(8.0, challenge * 1.5 + risk * 2.0);
        return positive - negative;
    }

    private double variance(List<Double> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        return values.stream().mapToDouble(value -> Math.pow(value - mean, 2)).average().orElse(0.0);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
