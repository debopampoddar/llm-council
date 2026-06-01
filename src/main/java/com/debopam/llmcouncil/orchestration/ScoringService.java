package com.debopam.llmcouncil.orchestration;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ScoringService {
    public ScoreSummary score(List<PeerReviewOutput> reviews) {
        Map<String, List<Integer>> scoresByDraft = new HashMap<>();
        for (PeerReviewOutput review : reviews) {
            for (DraftEvaluation evaluation : review.evaluations()) {
                int average = (int) Math.round(evaluation.scores().stream().mapToInt(CriterionScore::score).average().orElse(0));
                scoresByDraft.computeIfAbsent(evaluation.draftId(), ignored -> new ArrayList<>()).add(average);
            }
        }

        List<DraftScore> draftScores = scoresByDraft.entrySet().stream()
                .map(entry -> new DraftScore(entry.getKey(),
                        entry.getValue().stream().mapToInt(Integer::intValue).average().orElse(0),
                        entry.getValue().size()))
                .sorted(Comparator.comparingDouble(DraftScore::weightedScore).reversed())
                .toList();

        String winner = draftScores.isEmpty() ? null : draftScores.get(0).draftId();
        double variance = variance(draftScores.stream().map(DraftScore::weightedScore).toList());
        return new ScoreSummary(draftScores, variance, winner);
    }

    private double variance(List<Double> values) {
        if (values.isEmpty()) {
            return 0;
        }
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return values.stream().mapToDouble(value -> Math.pow(value - mean, 2)).average().orElse(0);
    }
}
