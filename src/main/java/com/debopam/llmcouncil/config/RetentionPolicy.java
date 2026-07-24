package com.debopam.llmcouncil.config;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Decides which entries a store should evict.
 *
 * <p>One implementation rather than four. The two rules that matter are easy to
 * get subtly wrong in a way nothing notices — evicting newest-first still
 * bounds memory, and evicting a live run's history still passes a size
 * assertion — so they are stated once, here, and every store defers to them:
 *
 * <ul>
 *   <li><b>Oldest first.</b> The entry least recently touched goes before one
 *       touched a minute ago. Anything else discards the history a user is most
 *       likely to still be looking at.</li>
 *   <li><b>Never evict an entry that is still in use.</b> A run in progress
 *       whose events were evicted mid-flight would stream a timeline with a hole
 *       in it, and the UI would present that hole as the run's actual history.
 *       Callers mark those entries protected; this class will not return one
 *       whatever the bounds say.</li>
 * </ul>
 *
 * <p>Protected entries still count toward {@code maxSessions}. They occupy real
 * memory, and excluding them would let a burst of concurrent runs push the store
 * arbitrarily far past its cap without a single eviction.
 */
public final class RetentionPolicy {

    private final RetentionSettings settings;

    /**
     * @param settings the bounds to enforce
     */
    public RetentionPolicy(RetentionSettings settings) {
        this.settings = settings;
    }

    /** @return the bounds this policy enforces */
    public RetentionSettings settings() {
        return settings;
    }

    /**
     * One store entry, reduced to what the decision actually depends on.
     *
     * @param key                  the entry's key in the store
     * @param lastActivity         when it was last written or touched
     * @param protectedFromEvicton {@code true} when the entry belongs to
     *                             something still in progress
     * @param <K>                  the store's key type
     */
    public record Candidate<K>(K key, Instant lastActivity, boolean protectedFromEvicton) {}

    /**
     * Choose the entries to remove.
     *
     * <p>Both bounds apply. An entry past {@code maxAgeDays} goes even when the
     * store is nearly empty — history nobody has touched in three months is not
     * kept just because there is room for it — and when the store is over
     * {@code maxSessions}, enough of the oldest remaining entries go to bring it
     * back to the cap.
     *
     * @param candidates every entry currently held
     * @param now        the current instant, supplied so age is testable without
     *                   sleeping
     * @param <K>        the store's key type
     * @return keys to evict, oldest first; empty when the store is within bounds
     */
    public <K> List<K> selectEvictions(Collection<Candidate<K>> candidates, Instant now) {
        List<Candidate<K>> evictable = new ArrayList<>(candidates.stream()
                .filter(candidate -> !candidate.protectedFromEvicton())
                .toList());
        // Oldest first, and stable for entries sharing an instant: two writes in
        // the same millisecond must not evict in an order that varies per run.
        evictable.sort(Comparator.comparing(Candidate::lastActivity));

        Instant cutoff = now.minusSeconds((long) settings.maxAgeDays() * 86_400L);
        List<K> evictions = new ArrayList<>();
        int index = 0;
        while (index < evictable.size() && evictable.get(index).lastActivity().isBefore(cutoff)) {
            evictions.add(evictable.get(index).key());
            index++;
        }

        // Size is measured against everything held, protected entries included:
        // they are memory too, even though they cannot be the ones released.
        int remaining = candidates.size() - evictions.size();
        while (remaining > settings.maxSessions() && index < evictable.size()) {
            evictions.add(evictable.get(index).key());
            index++;
            remaining--;
        }
        return evictions;
    }

    /**
     * How many of a session's oldest events to drop.
     *
     * @param eventCount how many events the session currently holds
     * @return the number to remove from the front, zero when within the cap
     */
    public int excessEvents(int eventCount) {
        return Math.max(0, eventCount - settings.maxEventsPerSession());
    }
}
