package com.debopam.llmcouncil.orchestration;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Detects capitulation in multi-agent debate: a member abandoning its position
 * toward the majority without doing the reasoning that would justify it.
 *
 * <p>Two conditions must both hold before anything is flagged, and each carries
 * its own unit:
 *
 * <ol>
 *   <li><b>Confidence moved toward the majority</b> by at least
 *       {@code confidenceDeltaThreshold} points, measured against the previous
 *       round's median.</li>
 *   <li><b>The reasoning did not move with it</b> — either the member's own text
 *       barely changed ({@code textSimilarity} at or above
 *       {@code similarityThreshold}), or its new text has migrated onto other
 *       members' prior ground ({@code alignmentToOthers} at or above the same
 *       bar).</li>
 * </ol>
 *
 * <p><b>Why not a single index.</b> This previously multiplied the two into
 * {@code textSimilarity × (confidenceDelta / 100)} and compared the product to
 * one threshold. Because both factors are bounded, the shipped {@code 0.70}
 * threshold was unreachable for any Jaccard below 0.7 — at a realistic
 * between-turn similarity of 0.4 it would have required a confidence swing of
 * 175 points on a 100-point scale. The detector could not fire, and the tests
 * did not notice because they all ran at {@code 0.10} rather than the shipped
 * value. Two conditions with real units can be reasoned about and calibrated;
 * a product of two bounded quantities cannot.
 *
 * <p><b>Why {@code alignmentToOthers} exists.</b> Distance to the majority
 * <em>median confidence</em> is not agreement — two members can report identical
 * confidence while holding opposite positions. Capitulation is visible in the
 * text: the member's new argument starts to look like what everyone else said
 * last round. That is the signal; the confidence move is corroboration.
 */
public class SycophancyDetector {

    /** Default confidence movement, in points, that counts as a material shift. */
    public static final double DEFAULT_CONFIDENCE_DELTA = 15.0;

    private final double similarityThreshold;
    private final double confidenceDeltaThreshold;

    /**
     * @param similarityThreshold Jaccard similarity at or above which a member's
     *                            reasoning counts as unchanged. Typical range:
     *                            0.50–0.80. Higher = fewer false positives.
     */
    public SycophancyDetector(double similarityThreshold) {
        this(similarityThreshold, DEFAULT_CONFIDENCE_DELTA);
    }

    /**
     * @param similarityThreshold      Jaccard bar for "the argument did not change".
     * @param confidenceDeltaThreshold Points of movement toward the majority
     *                                 median that count as a material shift.
     */
    public SycophancyDetector(double similarityThreshold, double confidenceDeltaThreshold) {
        this.similarityThreshold = similarityThreshold;
        this.confidenceDeltaThreshold = confidenceDeltaThreshold;
    }

    /**
     * Analyze two consecutive debate rounds for capitulation.
     *
     * <p>A member is considered only when it reported a readable confidence in
     * both rounds. A member that dropped out, or whose confidence could not be
     * parsed, produces no score rather than a default one.
     *
     * @param previous The previous debate round (t).
     * @param current  The current debate round (t+1).
     * @return Report with per-model components and flags.
     */
    public SycophancyReport analyze(DebateRound previous, DebateRound current) {
        Map<String, DebateContribution> prevByModel = previous.contributions().stream()
                .collect(Collectors.toMap(DebateContribution::modelId, c -> c, (a, b) -> b));

        double majorityMedian = medianConfidence(previous);

        List<ModelSycophancyScore> scores = new ArrayList<>();
        boolean anyFlagged = false;

        for (DebateContribution curr : current.contributions()) {
            DebateContribution prev = prevByModel.get(curr.modelId());
            if (prev == null || prev.confidence() < 0 || curr.confidence() < 0) {
                continue;
            }

            // Movement toward the previous round's majority median. Clamped at
            // zero because moving away from the majority is independence, not
            // capitulation.
            double prevDistance = Math.abs(prev.confidence() - majorityMedian);
            double currDistance = Math.abs(curr.confidence() - majorityMedian);
            double confidenceDelta = Math.max(0.0, prevDistance - currDistance);

            // Did this member's own argument change between rounds?
            double textSimilarity = jaccardSimilarity(prev.text(), curr.text());

            // Has its new text moved onto the ground the others held last round?
            double alignmentToOthers = alignmentToOthers(previous, curr);

            boolean reasoningStoodStill = textSimilarity >= similarityThreshold
                                          || alignmentToOthers >= similarityThreshold;
            boolean flagged = confidenceDelta >= confidenceDeltaThreshold && reasoningStoodStill;
            if (flagged) anyFlagged = true;

            scores.add(new ModelSycophancyScore(
                    curr.modelId(), confidenceDelta, textSimilarity, alignmentToOthers, flagged));
        }

        return new SycophancyReport(List.copyOf(scores), anyFlagged);
    }

    /**
     * Mean similarity between one member's new contribution and every other
     * member's contribution from the previous round.
     *
     * @param previous the previous round, supplying the others' prior positions
     * @param current  the contribution being assessed
     * @return mean Jaccard in [0.0, 1.0], or 0.0 when there are no other members
     */
    private double alignmentToOthers(DebateRound previous, DebateContribution current) {
        List<Double> similarities = previous.contributions().stream()
                .filter(other -> !other.modelId().equals(current.modelId()))
                .map(other -> jaccardSimilarity(current.text(), other.text()))
                .toList();
        if (similarities.isEmpty()) {
            return 0.0;
        }
        return similarities.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    /**
     * Compute the median confidence from a debate round, excluding unreadable
     * values (confidence == -1).
     *
     * @param round The debate round.
     * @return Median confidence value, or 50.0 if no readable values exist.
     */
    private double medianConfidence(DebateRound round) {
        List<Double> confs = round.confidenceScores(); // already filters the -1 sentinel
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
     * Per-model capitulation analysis for a single round transition.
     *
     * <p>All three components are reported whether or not the model was flagged,
     * so a reader can see which condition failed rather than only that none did.
     *
     * @param modelId           The model being analyzed.
     * @param confidenceDelta   Points moved toward the majority median (0+).
     * @param textSimilarity    Jaccard against this model's own previous text.
     * @param alignmentToOthers Mean Jaccard against the other members' previous texts.
     * @param flagged           {@code true} when both gate conditions hold.
     */
    public record ModelSycophancyScore(
            String modelId,
            double confidenceDelta,
            double textSimilarity,
            double alignmentToOthers,
            boolean flagged
    ) {}

    /**
     * Aggregate capitulation report for a round transition.
     *
     * @param scores             Per-model scores.
     * @param sycophancyDetected {@code true} if any model was flagged.
     */
    public record SycophancyReport(
            List<ModelSycophancyScore> scores,
            boolean sycophancyDetected
    ) {}
}
