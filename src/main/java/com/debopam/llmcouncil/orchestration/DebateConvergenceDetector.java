package com.debopam.llmcouncil.orchestration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides when a debate has stopped moving and can end early.
 *
 * <p>Two criteria live here because one statistic cannot serve both council
 * sizes:
 *
 * <ol>
 *   <li><b>Per-model confidence delta</b> — the default. Converged when every
 *       model that reported a readable confidence in both rounds moved less
 *       than {@code confidenceDelta} points. Defined and interpretable at the
 *       two-to-five members a real council actually has.</li>
 *   <li><b>Two-sample Kolmogorov–Smirnov</b> — used only once the paired sample
 *       reaches {@link #KS_MINIMUM_SAMPLE}. {@code D = max|F₁(x) − F₂(x)|},
 *       compared against {@code ksThreshold}.</li>
 * </ol>
 *
 * <p><b>Why the size gate exists.</b> KS on n samples is quantised to multiples
 * of {@code 1/n}. With three council members the only attainable values are
 * {@code 0, ⅓, ⅔, 1}, so against the usual {@code 0.10} threshold nothing short
 * of a byte-identical confidence multiset can ever converge. The statistic is
 * not wrong at that size — it is simply too coarse to express "close enough",
 * and a threshold no realistic input can cross is a feature that does not run.
 *
 * <p>Reference for the KS branch: "Multi-Agent Debate for LLM Judges with
 * Adaptive Stability Detection", NeurIPS 2025 (Hu et al.).
 */
public class DebateConvergenceDetector {

    /**
     * Paired-sample size at or above which the KS statistic is used instead of
     * the per-model delta. Below this, {@code 1/n} quantisation makes KS unable
     * to distinguish "close" from "far".
     */
    public static final int KS_MINIMUM_SAMPLE = 8;

    /** Default maximum per-model confidence movement that still counts as stable. */
    public static final double DEFAULT_CONFIDENCE_DELTA = 5.0;

    private final double ksThreshold;
    private final double confidenceDelta;

    /**
     * @param ksThreshold Stop debate when KS distance between consecutive rounds
     *                    is below this value. Typical range: 0.05–0.20. Only
     *                    consulted once the paired sample reaches
     *                    {@link #KS_MINIMUM_SAMPLE}.
     */
    public DebateConvergenceDetector(double ksThreshold) {
        this(ksThreshold, DEFAULT_CONFIDENCE_DELTA);
    }

    /**
     * @param ksThreshold     KS distance below which large councils are converged.
     * @param confidenceDelta Maximum per-model confidence movement, in points on
     *                        the 0–100 scale, that still counts as stable for a
     *                        small council.
     */
    public DebateConvergenceDetector(double ksThreshold, double confidenceDelta) {
        this.ksThreshold = ksThreshold;
        this.confidenceDelta = confidenceDelta;
    }

    /**
     * Returns {@code true} when positions between two rounds have stopped moving.
     *
     * <p>Models are paired by id, and a model is considered only when it
     * reported a readable confidence in <em>both</em> rounds — a model that
     * dropped out or whose confidence could not be read carries no evidence
     * either way. When nothing can be paired, this returns {@code false}:
     * "no evidence of movement" is not "evidence of no movement", and debate
     * ending early on an unmeasurable round would be the sycophantic
     * early-agreement failure this check exists to prevent.
     *
     * @param previous the previous debate round
     * @param current  the round just completed
     * @return {@code true} when the debate can stop
     */
    public boolean hasConverged(DebateRound previous, DebateRound current) {
        if (previous == null || current == null) {
            return false;
        }
        Map<String, Double> paired = pairedConfidences(previous, current);
        if (paired.isEmpty()) {
            return false;
        }
        if (paired.size() >= KS_MINIMUM_SAMPLE) {
            return hasConverged(previous.confidenceScores(), current.confidenceScores());
        }
        return paired.values().stream().allMatch(movement -> movement <= confidenceDelta);
    }

    /**
     * Returns {@code true} if the confidence score distributions from two
     * consecutive rounds are similar enough to stop debating.
     *
     * <p>This is the distribution-level test only. Prefer
     * {@link #hasConverged(DebateRound, DebateRound)}, which picks the criterion
     * appropriate to the council's size.
     *
     * @param prevScores Confidence scores from round t.
     * @param currScores Confidence scores from round t+1.
     * @return {@code true} when the KS distance is below the configured threshold
     */
    public boolean hasConverged(List<Double> prevScores, List<Double> currScores) {
        if (prevScores == null || currScores == null
            || prevScores.isEmpty() || currScores.isEmpty()) {
            return false;
        }
        return ksStat(prevScores, currScores) < ksThreshold;
    }

    /**
     * Absolute confidence movement per model, for models readable in both rounds.
     *
     * @param previous round t
     * @param current  round t+1
     * @return model id to absolute confidence change, in points
     */
    private Map<String, Double> pairedConfidences(DebateRound previous, DebateRound current) {
        Map<String, Integer> before = new LinkedHashMap<>();
        for (DebateContribution c : previous.contributions()) {
            if (c.confidence() >= 0) {
                before.put(c.modelId(), c.confidence());
            }
        }
        Map<String, Double> movement = new LinkedHashMap<>();
        for (DebateContribution c : current.contributions()) {
            Integer prior = before.get(c.modelId());
            if (prior != null && c.confidence() >= 0) {
                movement.put(c.modelId(), Math.abs((double) c.confidence() - prior));
            }
        }
        return movement;
    }

    /**
     * Compute the two-sample KS statistic: the largest gap between the two
     * empirical CDFs.
     *
     * <p>The sweep visits each <em>distinct</em> value present in either sample
     * and consumes every tied observation at that value before comparing the two
     * CDFs. Sampling mid-tie is what made the previous implementation return
     * {@code 1.0} for two identical samples — the maximum possible distance for
     * inputs that are not different at all.
     *
     * <p>No tail pass is needed. Once one sample is exhausted its CDF is pinned
     * at 1.0 while the other only climbs toward 1.0, so the gap shrinks
     * monotonically and the maximum has already been seen.
     *
     * @param a First sample.
     * @param b Second sample.
     * @return KS statistic in [0, 1]; {@code 0.0} for identical samples.
     */
    double ksStat(List<Double> a, List<Double> b) {
        List<Double> sa = a.stream().sorted().toList();
        List<Double> sb = b.stream().sorted().toList();
        int n = sa.size(), m = sb.size(), i = 0, j = 0;
        double maxDiff = 0.0;
        while (i < n && j < m) {
            double x = Math.min(sa.get(i), sb.get(j));
            while (i < n && sa.get(i) <= x) i++;
            while (j < m && sb.get(j) <= x) j++;
            maxDiff = Math.max(maxDiff, Math.abs((double) i / n - (double) j / m));
        }
        return maxDiff;
    }
}
