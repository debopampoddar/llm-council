package com.debopam.llmcouncil.application;

import com.debopam.llmcouncil.config.RetentionPolicy;
import com.debopam.llmcouncil.config.RetentionSettings;
import com.debopam.llmcouncil.domain.CouncilEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-session sequence an event store assigns on append.
 *
 * <p>{@code occurredAt} cannot do this job. Under virtual-thread fan-out a
 * council emits many events inside one millisecond, so ordering by timestamp
 * leaves ties the store is free to break differently on each read — and a
 * timeline that reorders itself between two loads of the same finished run reads
 * as a council that ran its stages out of order.
 *
 * <p>The sequence is also what a reconnect cursor will seek to, which is why the
 * numbers must never be reissued: a repeated number would make a client skip an
 * event it had not seen while replaying one it had.
 */
class EventSequenceTest {

    @Test
    void sequencesStartAtOneAndCountUpWithinASession() {
        // Starting at 1 leaves 0 free to mean "everything you have", so a
        // client with no cursor yet does not have to special-case its first
        // request.
        InMemoryEventStore store = new InMemoryEventStore();

        List<Long> assigned = IntStream.range(0, 4)
                .mapToObj(index -> store.append(event("s1", "EVENT_" + index)).seq())
                .toList();

        assertEquals(List.of(1L, 2L, 3L, 4L), assigned);
        assertEquals(CouncilEvent.UNASSIGNED_SEQ, event("s1", "NOT_STORED").seq(),
                     "an event that has not been appended carries no sequence");
    }

    @Test
    void eachSessionHasItsOwnSequence() {
        // A shared counter would still be monotonic, and every ordering
        // assertion would still pass; what breaks is the cursor, which is
        // per session and would then skip most of a session's history.
        InMemoryEventStore store = new InMemoryEventStore();

        store.append(event("s1", "A"));
        store.append(event("s2", "A"));
        store.append(event("s1", "B"));

        assertEquals(List.of(1L, 2L),
                     store.history("s1").stream().map(CouncilEvent::seq).toList());
        assertEquals(List.of(1L), store.history("s2").stream().map(CouncilEvent::seq).toList());
    }

    @Test
    void theStoredEventCarriesTheSequenceTheAppendReturned() {
        InMemoryEventStore store = new InMemoryEventStore();

        CouncilEvent returned = store.append(event("s1", "ONLY"));

        assertEquals(returned, store.history("s1").getFirst());
        assertEquals(1L, store.history("s1").getFirst().seq());
    }

    @Test
    void trimmingOldEventsDoesNotReissueTheirSequences() {
        // The per-session cap drops the oldest events. A store that derived seq
        // from its list size would hand the next event a number it had already
        // used, and a cursor at that position would skip everything since.
        InMemoryEventStore store = new InMemoryEventStore(
                new RetentionPolicy(new RetentionSettings(500, 90, 3)), new RunRegistry());

        for (int index = 0; index < 6; index++) {
            store.append(event("s1", "EVENT_" + index));
        }

        assertEquals(List.of(4L, 5L, 6L),
                     store.history("s1").stream().map(CouncilEvent::seq).toList(),
                     "the survivors keep the numbers they were given");
    }

    @Test
    void concurrentAppendsNeverShareASequence() {
        // Events are appended from the virtual threads running a council, many
        // of them at once. A non-atomic counter would duplicate under exactly
        // that load and never under a sequential test.
        InMemoryEventStore store = new InMemoryEventStore();
        int eventCount = 500;

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<CouncilEvent>> futures = IntStream.range(0, eventCount)
                    .mapToObj(index ->
                            executor.submit(() -> store.append(event("s1", "EVENT_" + index))))
                    .toList();
            Set<Long> distinct = futures.stream().map(EventSequenceTest::get)
                                        .map(CouncilEvent::seq)
                                        .collect(java.util.stream.Collectors.toSet());

            assertEquals(eventCount, distinct.size(), "every append got its own number");
            assertTrue(distinct.contains(1L) && distinct.contains((long) eventCount),
                       "and the numbers run 1..n with no gaps");
            assertEquals(IntStream.rangeClosed(1, eventCount).mapToObj(Long::valueOf).toList(),
                         store.history("s1").stream().map(CouncilEvent::seq).toList(),
                         "timeline history is returned in sequence order after concurrent appends");
        }
    }

    private static CouncilEvent get(Future<CouncilEvent> future) {
        try {
            return future.get();
        } catch (Exception ex) {
            throw new IllegalStateException("Append failed", ex);
        }
    }

    private CouncilEvent event(String sessionId, String type) {
        return CouncilEvent.of(sessionId, "GENERATE", type, null, Map.of());
    }
}
