package com.debopam.llmcouncil.orchestration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DebateConvergenceDetector}.
 *
 * <p>Every KS expectation below is derived from the definition of the
 * two-sample Kolmogorov–Smirnov statistic — {@code D = max|F₁(x) − F₂(x)|} —
 * and hand-computed, never captured from a run. The previous version of this
 * class asserted {@code ks(a,a) == 0.25}, and {@code 1.0} for two identical
 * single-element samples, describing both as "a known property of the
 * algorithm's step function". Neither is a property of the statistic: the
 * distance between a distribution and itself is zero. Those assertions were the
 * defect written down as if it were the specification, and they kept the
 * detector from ever firing below an eleven-member council.
 */
class DebateConvergenceDetectorTest {

    private final DebateConvergenceDetector detector = new DebateConvergenceDetector(0.10);

    // ── KS statistic, against the definition

    @Test
    @DisplayName("the distance between a distribution and itself is zero")
    void identicalDistributionsProduceZeroKs() {
        assertEquals(0.0, detector.ksStat(List.of(70.0, 75.0, 80.0, 85.0),
                                          List.of(70.0, 75.0, 80.0, 85.0)), 1e-9);
    }

    @Test
    @DisplayName("tied observations do not manufacture distance")
    void tiedSamplesProduceZeroKs() {
        // The exact regression: sampling the CDFs mid-tie returned 1.0 here —
        // maximum possible distance for two samples that are not different at all.
        assertEquals(0.0, detector.ksStat(List.of(70.0, 70.0, 70.0),
                                          List.of(70.0, 70.0, 70.0)), 1e-9);
    }

    @Test
    @DisplayName("two identical single-element samples are at distance zero")
    void ksStatHandlesSingleElement() {
        assertEquals(0.0, detector.ksStat(List.of(50.0), List.of(50.0)), 1e-9);
    }

    @Test
    @DisplayName("disjoint distributions are at maximum distance")
    void disjointDistributionsYieldKsOfOne() {
        assertEquals(1.0, detector.ksStat(List.of(1.0, 2.0, 3.0),
                                          List.of(10.0, 20.0, 30.0)), 1e-9);
    }

    @Test
    @DisplayName("a uniform one-point shift of three values gives D = 1/3")
    void ksStatOfShiftedSample() {
        // F₁ reaches 1/3 at x=70 while F₂ is still 0 — the largest gap. Every
        // later step either closes to 0 or reopens to the same 1/3.
        assertEquals(1.0 / 3.0, detector.ksStat(List.of(70.0, 75.0, 80.0),
                                                List.of(71.0, 76.0, 81.0)), 1e-9);
    }

    @Test
    @DisplayName("half of one sample displaced gives D = 1/2")
    void ksStatOfHalfDisplacedSample() {
        // a = [1,2,3,4], b = [3,4,5,6]. At x=2, F₁ = 2/4 and F₂ = 0.
        assertEquals(0.5, detector.ksStat(List.of(1.0, 2.0, 3.0, 4.0),
                                          List.of(3.0, 4.0, 5.0, 6.0)), 1e-9);
    }

    @Test
    @DisplayName("KS is symmetric in its arguments")
    void ksStatSymmetric() {
        List<Double> a = List.of(50.0, 60.0, 70.0);
        List<Double> b = List.of(55.0, 65.0, 75.0);
        assertEquals(detector.ksStat(a, b), detector.ksStat(b, a), 1e-9);
    }

    @Test
    @DisplayName("unequal sample sizes stay in range")
    void ksStatHandlesUnequalLengths() {
        double ks = detector.ksStat(List.of(50.0, 60.0, 70.0, 80.0, 90.0), List.of(55.0, 65.0));
        assertTrue(ks >= 0.0 && ks <= 1.0, "KS must be in [0,1], got " + ks);
    }

    // ── Convergence at the sizes a council actually has

    @Test
    @DisplayName("a three-member council converges when nobody moved")
    void threeMemberCouncilConvergesWhenStable() {
        // The size that matters: this is what local-rigorous runs. Under the old
        // implementation no council below eleven members could ever converge.
        assertTrue(detector.hasConverged(round(0, 80, 75, 70), round(1, 82, 74, 72)),
                   "movements of 2, 1 and 2 points are all within the 5-point default");
    }

    @Test
    @DisplayName("a three-member council does not converge while a member is still moving")
    void threeMemberCouncilDoesNotConvergeWhileMoving() {
        // Positive control for the test above: the detector must be able to
        // return false, or "converges when stable" proves nothing.
        assertFalse(detector.hasConverged(round(0, 80, 75, 70), round(1, 82, 74, 45)),
                    "a 25-point swing is not a stable position");
    }

    @Test
    @DisplayName("the delta threshold is honoured at its boundary")
    void deltaThresholdBoundary() {
        DebateConvergenceDetector strict = new DebateConvergenceDetector(0.10, 5.0);
        assertTrue(strict.hasConverged(round(0, 80), round(1, 85)),
                   "movement exactly at the threshold counts as stable");
        assertFalse(strict.hasConverged(round(0, 80), round(1, 86)),
                    "movement past the threshold does not");
    }

    @Test
    @DisplayName("unreadable confidence is not evidence of stability")
    void unreadableConfidenceDoesNotConverge() {
        // A model whose confidence could not be read carries no evidence either
        // way. Treating its absence as agreement is the early-agreement failure
        // that min-rounds exists to prevent.
        DebateRound prev = new DebateRound(0, List.of(
                new DebateContribution("model-a", "position", -1)));
        DebateRound curr = new DebateRound(1, List.of(
                new DebateContribution("model-a", "position", -1)));
        assertFalse(detector.hasConverged(prev, curr));
    }

    @Test
    @DisplayName("a model appearing in only one round is not paired")
    void unpairedModelIsIgnored() {
        DebateRound prev = new DebateRound(0, List.of(
                new DebateContribution("model-a", "position", 70)));
        DebateRound curr = new DebateRound(1, List.of(
                new DebateContribution("model-b", "position", 71)));
        assertFalse(detector.hasConverged(prev, curr),
                    "nothing was paired, so nothing was measured");
    }

    @Test
    @DisplayName("a large council switches to the KS criterion")
    void largeCouncilUsesKsDistance() {
        // At or above KS_MINIMUM_SAMPLE the distribution test takes over.
        // Identical distributions now give D = 0, so this converges.
        int n = DebateConvergenceDetector.KS_MINIMUM_SAMPLE;
        assertTrue(detector.hasConverged(roundOfSize(0, n, 80), roundOfSize(1, n, 80)));
    }

    @Test
    @DisplayName("a large council with a shifted distribution does not converge")
    void largeCouncilDivergentDoesNotConverge() {
        int n = DebateConvergenceDetector.KS_MINIMUM_SAMPLE;
        assertFalse(detector.hasConverged(roundOfSize(0, n, 20), roundOfSize(1, n, 90)));
    }

    @Test
    @DisplayName("large-council KS ignores members that cannot be paired")
    void largeCouncilKsUsesOnlyPairedMembers() {
        int n = DebateConvergenceDetector.KS_MINIMUM_SAMPLE;
        List<DebateContribution> before = new ArrayList<>(roundOfSize(0, n, 80).contributions());
        List<DebateContribution> after = new ArrayList<>(roundOfSize(1, n, 80).contributions());
        before.add(new DebateContribution("dropout", "old", 0));
        after.add(new DebateContribution("newcomer", "new", 100));

        assertTrue(detector.hasConverged(
                new DebateRound(0, before), new DebateRound(1, after)),
                "unpaired extremes carry no between-round evidence");
    }

    // ── Null and empty handling

    @Test
    void nullRoundsDoNotConverge() {
        assertFalse(detector.hasConverged((DebateRound) null, round(1, 70)));
        assertFalse(detector.hasConverged(round(0, 70), (DebateRound) null));
    }

    @Test
    void nullAndEmptyScoreListsDoNotConverge() {
        assertFalse(detector.hasConverged(null, List.of(70.0)));
        assertFalse(detector.hasConverged(List.of(70.0), null));
        assertFalse(detector.hasConverged(List.of(), List.of()));
    }

    // ── Threshold plumbing

    @Test
    void looseThresholdConvergesEasier() {
        DebateConvergenceDetector loose = new DebateConvergenceDetector(0.50);
        assertTrue(loose.hasConverged(List.of(70.0, 75.0, 80.0), List.of(72.0, 77.0, 82.0)));
    }

    @Test
    void tightThresholdPreventsConvergence() {
        DebateConvergenceDetector tight = new DebateConvergenceDetector(0.01);
        assertFalse(tight.hasConverged(List.of(70.0, 75.0, 80.0), List.of(71.0, 74.0, 79.0)));
    }

    // ── Fixtures

    /** A round whose members are model-0, model-1, … with the given confidences. */
    private static DebateRound round(int number, int... confidences) {
        List<DebateContribution> contributions = new ArrayList<>();
        for (int i = 0; i < confidences.length; i++) {
            contributions.add(new DebateContribution("model-" + i, "position " + i, confidences[i]));
        }
        return new DebateRound(number, List.copyOf(contributions));
    }

    /** A round of {@code size} members all reporting the same confidence. */
    private static DebateRound roundOfSize(int number, int size, int confidence) {
        int[] confidences = new int[size];
        java.util.Arrays.fill(confidences, confidence);
        return round(number, confidences);
    }
}
