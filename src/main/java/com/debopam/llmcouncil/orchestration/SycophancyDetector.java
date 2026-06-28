package com.debopam.llmcouncil.orchestration;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Detects sycophantic behavior in multi-agent debate by measuring how quickly
 * and dramatically agents shift their positions toward the majority.
 *
 * <p><b>Gap 1.3 (Sycophancy Detection Metrics):</b> research shows that rapid
 * capitulation to majority opinion without substantive argument changes is the
 * primary sycophancy signal in LLM councils. This detector computes two metrics:
 * <ol>
 *   <li><b>Confidence delta toward majority</b>: how much a model's confidence
 *       shifted toward the group median between rounds.</li>
 *   <li><b>Text similarity (Jaccard)</b>: word-level overlap between a model's
 *       consecutive contributions. High similarity + confidence shift = sycophancy.</li>
 * </ol>
 *
 * <p>Sycophancy index formula: {@code textSimilarity * (confidenceDelta / 100.0)}.
 * A high index means the model changed its stated confidence substantially toward
 * the majority without meaningfully changing its argument text.
 */
public class SycophancyDetector {

    private final double threshold;

    /**
     * @param threshold sycophancy index threshold above which a model is flagged.
     *                  Typical range: 0.50–0.80. Higher = fewer false positives.
     */
    public SycophancyDetector(double threshold) {
        this.threshold = threshold;
    }

    /**
     * Analyze two consecutive debate rounds for sycophancy signals.
     *
     * <p>For each model present in both rounds, computes:
     * <ol>
     *   <li>How much its confidence moved toward the majority median.</li>
     *   <li>How similar its text was between rounds (Jaccard word overlap).</li>
     *   <li>A combined sycophancy index: high text similarity * high confidence
     *       shift toward majority = likely sycophantic behavior.</li>
     * </ol>
     *
     * @param previous The previous debate round (t).
     * @param current  The current debate round (t+1).
     * @return Report with per-model sycophancy scores.
     */
    public SycophancyReport analyze(DebateRound previous, DebateRound current) {
        // Build lookup of previous round contributions by model ID
        Map<String, DebateContribution> prevByModel = previous.contributions().stream()
                .collect(Collectors.toMap(DebateContribution::modelId, c -> c, (a, b) -> b));

        // Compute the majority median confidence from the previous round
        // (only include parseable confidence values, i.e., >= 0)
        double majorityMedian = medianConfidence(previous);

        List<ModelSycophancyScore> scores = new ArrayList<>();
        boolean anyFlagged = false;

        for (DebateContribution curr : current.contributions()) {
            DebateContribution prev = prevByModel.get(curr.modelId());
            // Cannot compute sycophancy without both rounds' confidence values
            if (prev == null || prev.confidence() < 0 || curr.confidence() < 0) {
                continue;
            }

            // How far was the model from majority before and after?
            double prevDistance = Math.abs(prev.confidence() - majorityMedian);
            double currDistance = Math.abs(curr.confidence() - majorityMedian);

            // confidenceDelta > 0 means moved toward majority; <= 0 means moved away
            // Clamped to zero because moving away from majority is not sycophantic.
            double confidenceDelta = Math.max(0.0, prevDistance - currDistance);

            // Compute text similarity (Jaccard word-level) between consecutive
            // contributions from the same model.
            double textSimilarity = jaccardSimilarity(prev.text(), curr.text());

            // Sycophancy index: high text similarity (argument barely changed) *
            // high confidence shift toward majority (opinion changed anyway)
            // = model capitulated without substantive new reasoning.
            double sycophancyIndex = textSimilarity * (confidenceDelta / 100.0);

            boolean flagged = sycophancyIndex >= threshold;
            if (flagged) anyFlagged = true;

            scores.add(new ModelSycophancyScore(
                    curr.modelId(), confidenceDelta, textSimilarity, sycophancyIndex, flagged));
        }

        return new SycophancyReport(List.copyOf(scores), anyFlagged);
    }

    /**
     * Compute the median confidence from a debate round, excluding unparseable
     * values (confidence == -1).
     *
     * @param round The debate round.
     * @return Median confidence value, or 50.0 if no parseable values exist.
     */
    private double medianConfidence(DebateRound round) {
        List<Double> confs = round.confidenceScores(); // already filters -1 sentinel
        if (confs.isEmpty()) return 50.0; // neutral default when no confidence data
        List<Double> sorted = confs.stream().sorted().toList();
        int n = sorted.size();
        if (n % 2 == 0) {
            return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
        }
        return sorted.get(n / 2);
    }

    /**
     * Jaccard similarity between two texts at the word level.
     * {@code J(A,B) = |A ∩ B| / |A ∪ B|}
     *
     * <p>Words are lowercased and split on whitespace. Punctuation is not
     * stripped so that code-containing debates don't lose signal.
     *
     * @param text1 First text.
     * @param text2 Second text.
     * @return Similarity in [0.0, 1.0]. Returns 0.0 if either text is null/empty.
     */
    static double jaccardSimilarity(String text1, String text2) {
        if (text1 == null || text2 == null || text1.isBlank() || text2.isBlank()) {
            return 0.0;
        }
        Set<String> words1 = new HashSet<>(Arrays.asList(text1.toLowerCase().split("\\s+")));
        Set<String> words2 = new HashSet<>(Arrays.asList(text2.toLowerCase().split("\\s+")));

        Set<String> intersection = new HashSet<>(words1);
        intersection.retainAll(words2);

        Set<String> union = new HashSet<>(words1);
        union.addAll(words2);

        if (union.isEmpty()) return 0.0;
        return (double) intersection.size() / union.size();
    }

    // ── Inner records 

    /**
     * Per-model sycophancy analysis for a single round transition.
     *
     * @param modelId         The model being analyzed.
     * @param confidenceDelta How much confidence moved toward majority (0+).
     * @param textSimilarity  Jaccard word-level similarity (0.0–1.0).
     * @param sycophancyIndex Combined score: textSimilarity * (confidenceDelta/100).
     * @param flagged         {@code true} if index exceeds the configured threshold.
     */
    public record ModelSycophancyScore(
            String modelId,
            double confidenceDelta,
            double textSimilarity,
            double sycophancyIndex,
            boolean flagged
    ) {}

    /**
     * Aggregate sycophancy report for a round transition.
     *
     * @param scores             Per-model sycophancy scores.
     * @param sycophancyDetected {@code true} if any model was flagged.
     */
    public record SycophancyReport(
            List<ModelSycophancyScore> scores,
            boolean sycophancyDetected
    ) {}
}
