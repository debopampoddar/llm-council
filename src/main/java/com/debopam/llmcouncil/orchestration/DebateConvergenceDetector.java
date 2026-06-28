package com.debopam.llmcouncil.orchestration;

import java.util.List;

/**
 * Detects convergence in multi-agent debate using the two-sample
 * Kolmogorov–Smirnov (KS) statistic.
 *
 * <p>Reference: "Multi-Agent Debate for LLM Judges with Adaptive Stability
 * Detection", NeurIPS 2025 (Hu et al.). The KS statistic measures the maximum
 * distance between two empirical CDFs. When it falls below a threshold, the
 * score distributions are considered stable and debate can stop early.
 *
 * <p>KS statistic: {@code D = max|F1(x) - F2(x)|}.
 * Typical threshold: {@code 0.10} (10% maximum CDF gap).
 */
public class DebateConvergenceDetector {

    private final double ksThreshold;

    /**
     * @param ksThreshold Stop debate when KS distance between consecutive rounds
     *                    is below this value. Typical range: 0.05–0.20.
     */
    public DebateConvergenceDetector(double ksThreshold) {
        this.ksThreshold = ksThreshold;
    }

    /**
     * Returns {@code true} if the confidence score distributions from two
     * consecutive rounds are similar enough to stop debating.
     *
     * @param prevScores Confidence scores from round t.
     * @param currScores Confidence scores from round t+1.
     */
    public boolean hasConverged(List<Double> prevScores, List<Double> currScores) {
        if (prevScores == null || currScores == null
            || prevScores.isEmpty() || currScores.isEmpty()) {
            return false;
        }
        return ksStat(prevScores, currScores) < ksThreshold;
    }

    /**
     * Compute the two-sample KS statistic between two lists of values.
     * Both lists are sorted to build empirical CDFs; the maximum absolute
     * difference is returned.
     *
     * <p>The merge walk advances through both sorted lists simultaneously.
     * After one list is exhausted, the remaining elements in the other list
     * must still be checked — their CDF steps can produce new maxima that
     * were missed by the original implementation.
     *
     * @param a First sample.
     * @param b Second sample.
     * @return KS statistic in [0, 1].
     */
    double ksStat(List<Double> a, List<Double> b) {
        List<Double> sa = a.stream().sorted().toList();
        List<Double> sb = b.stream().sorted().toList();
        int n = sa.size(), m = sb.size(), i = 0, j = 0;
        double maxDiff = 0.0;
        while (i < n && j < m) {
            if (sa.get(i) <= sb.get(j)) i++;
            else j++;
            double fa = (double) i / n;
            double fb = (double) j / m;
            maxDiff = Math.max(maxDiff, Math.abs(fa - fb));
        }
        // Account for remaining elements in the longer list. Each step
        // advances one CDF while the other stays at 1.0, so new maxima
        // are possible.
        while (i < n) {
            i++;
            maxDiff = Math.max(maxDiff, Math.abs((double) i / n - (double) j / m));
        }
        while (j < m) {
            j++;
            maxDiff = Math.max(maxDiff, Math.abs((double) i / n - (double) j / m));
        }
        return maxDiff;
    }
}
