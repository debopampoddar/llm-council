package com.debopam.llmcouncil.config;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The eviction decision itself (F5).
 *
 * <p>The two rules here are the ones that fail silently when they are wrong.
 * Evicting newest-first still bounds memory and still passes a size assertion —
 * it just throws away the history someone is most likely to be looking at. And
 * evicting a live run's entry still passes every count-based check; it only
 * shows up later as a timeline with a hole in it that the UI presents as stages
 * that never ran.
 *
 * <p>So every assertion that something <em>survived</em> sits beside one that
 * something else was evicted in the same call. Without that control, a policy
 * that evicted nothing at all would pass the whole file.
 */
class RetentionPolicyTest {

    private static final Instant NOW = Instant.parse("2026-07-24T00:00:00Z");

    // ── Ordering

    @Test
    void evictsOldestFirst() {
        RetentionPolicy policy = policy(2, 90);

        List<String> evicted = policy.selectEvictions(List.of(
                candidate("newest", NOW),
                candidate("oldest", NOW.minus(10, ChronoUnit.DAYS)),
                candidate("middle", NOW.minus(5, ChronoUnit.DAYS))), NOW);

        assertEquals(List.of("oldest"), evicted,
                     "the least recently touched entry goes first; evicting the newest would "
                     + "bound memory while discarding what the user is still looking at");
    }

    @Test
    void evictsAsManyAsTheCapRequiresAndNoMore() {
        RetentionPolicy policy = policy(2, 90);

        List<String> evicted = policy.selectEvictions(List.of(
                candidate("a", NOW.minus(4, ChronoUnit.DAYS)),
                candidate("b", NOW.minus(3, ChronoUnit.DAYS)),
                candidate("c", NOW.minus(2, ChronoUnit.DAYS)),
                candidate("d", NOW.minus(1, ChronoUnit.DAYS)),
                candidate("e", NOW)), NOW);

        assertEquals(List.of("a", "b", "c"), evicted, "five entries down to a cap of two");
    }

    @Test
    void aStoreWithinBothBoundsLosesNothing() {
        // Positive control for every survival assertion below: eviction has to
        // be capable of returning nothing, or "X survived" proves nothing.
        RetentionPolicy policy = policy(10, 90);

        assertTrue(policy.selectEvictions(List.of(
                candidate("a", NOW.minus(3, ChronoUnit.DAYS)),
                candidate("b", NOW)), NOW).isEmpty());
    }

    // ── Age

    @Test
    void ageEvictsEvenWhenThereIsPlentyOfRoom() {
        RetentionPolicy policy = policy(1000, 90);

        List<String> evicted = policy.selectEvictions(List.of(
                candidate("ancient", NOW.minus(91, ChronoUnit.DAYS)),
                candidate("recent", NOW.minus(89, ChronoUnit.DAYS))), NOW);

        assertEquals(List.of("ancient"), evicted,
                     "history nobody has touched in three months is not kept merely because "
                     + "there is space for it");
    }

    @Test
    void theAgeBoundIsExclusiveAtItsBoundary() {
        RetentionPolicy policy = policy(1000, 90);

        assertTrue(policy.selectEvictions(
                List.of(candidate("exactly-90-days", NOW.minus(90, ChronoUnit.DAYS))), NOW).isEmpty(),
                   "an entry exactly at the bound has not passed it");
        assertFalse(policy.selectEvictions(
                List.of(candidate("just-over", NOW.minus(90, ChronoUnit.DAYS).minusSeconds(1))), NOW)
                          .isEmpty(),
                    "a second past the bound has");
    }

    // ── Protection

    @Test
    void anEntryStillInUseIsNeverEvictedHoweverOldItIs() {
        RetentionPolicy policy = policy(1, 90);

        List<String> evicted = policy.selectEvictions(List.of(
                protectedCandidate("running", NOW.minus(500, ChronoUnit.DAYS)),
                candidate("finished", NOW.minus(499, ChronoUnit.DAYS))), NOW);

        assertFalse(evicted.contains("running"),
                    "the oldest entry by far, and past both bounds, but a run is still writing "
                    + "to it — evicting it leaves a live timeline with a hole in it");
        // The control: the machinery did fire on this call, so the survival
        // above is protection working rather than eviction not happening.
        assertEquals(List.of("finished"), evicted);
    }

    @Test
    void aStoreOfNothingButLiveRunsEvictsNothing() {
        RetentionPolicy policy = policy(1, 1);

        assertTrue(policy.selectEvictions(List.of(
                protectedCandidate("run-1", NOW.minus(400, ChronoUnit.DAYS)),
                protectedCandidate("run-2", NOW.minus(400, ChronoUnit.DAYS))), NOW).isEmpty(),
                   "the bounds are exceeded on both counts, and the protection rule still wins");
    }

    @Test
    void protectedEntriesCountTowardTheSizeCap() {
        // Otherwise a burst of concurrent runs pushes the store arbitrarily past
        // its cap without a single eviction, which is the growth this closes.
        RetentionPolicy policy = policy(2, 90);

        List<String> evicted = policy.selectEvictions(List.of(
                protectedCandidate("running-1", NOW.minus(2, ChronoUnit.DAYS)),
                protectedCandidate("running-2", NOW.minus(2, ChronoUnit.DAYS)),
                candidate("done-1", NOW.minus(1, ChronoUnit.DAYS)),
                candidate("done-2", NOW)), NOW);

        assertEquals(List.of("done-1", "done-2"), evicted,
                     "four entries against a cap of two: the two protected ones occupy the cap, "
                     + "so both finished entries go");
    }

    // ── Per-session event cap

    @Test
    void eventsOverTheCapAreCountedForRemoval() {
        RetentionPolicy policy = new RetentionPolicy(new RetentionSettings(500, 90, 100));

        assertEquals(0, policy.excessEvents(100), "exactly at the cap is within it");
        assertEquals(1, policy.excessEvents(101));
        assertEquals(0, policy.excessEvents(4), "a short run loses nothing");
    }

    // ── Clamping

    @Test
    void aCapOfZeroIsClampedRatherThanDeletingEverythingOnWrite() {
        RetentionSettings settings = new RetentionSettings(0, 0, 0);

        assertEquals(1, settings.maxSessions(),
                     "a cap of zero would evict every entry the instant it was written, so a "
                     + "finished run would lose its own result");
        assertEquals(1, settings.maxAgeDays());
        assertEquals(1, settings.maxEventsPerSession());
    }

    @Test
    void anUnmentionedBoundKeepsItsCurrentValue() {
        RetentionSettings merged = RetentionSettings.DEFAULTS.withOverrides(50, null, null);

        assertEquals(50, merged.maxSessions());
        assertEquals(90, merged.maxAgeDays(), "setting one bound must not reset the others");
        assertEquals(2000, merged.maxEventsPerSession());
    }

    // ── Fixtures

    private RetentionPolicy policy(int maxSessions, int maxAgeDays) {
        return new RetentionPolicy(new RetentionSettings(maxSessions, maxAgeDays, 2000));
    }

    private RetentionPolicy.Candidate<String> candidate(String key, Instant lastActivity) {
        return new RetentionPolicy.Candidate<>(key, lastActivity, false);
    }

    private RetentionPolicy.Candidate<String> protectedCandidate(String key, Instant lastActivity) {
        return new RetentionPolicy.Candidate<>(key, lastActivity, true);
    }
}
