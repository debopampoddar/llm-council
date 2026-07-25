package com.debopam.llmcouncil.application;

import com.debopam.llmcouncil.domain.CouncilEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What every {@link EventStore} must do, whatever it stores events in.
 *
 * <p>The event store is the one the timeline is drawn from, so its failures are
 * the ones a reader sees as a council that behaved strangely rather than as a
 * bug: stages out of order, a stage that appears twice, a model attribution
 * moved to the wrong row.
 */
public abstract class EventStoreContractTest {

    /**
     * @return a fresh, empty store of the implementation under test
     */
    protected abstract EventStore createStore();

    @Test
    void appendAssignsSequencesFromOnePerSession() {
        EventStore store = createStore();

        List<Long> assigned = IntStream.range(0, 3)
                .mapToObj(index -> store.append(event("s1", "EVENT_" + index)).seq())
                .toList();

        assertEquals(List.of(1L, 2L, 3L), assigned);
    }

    @Test
    void eachSessionHasItsOwnSequence() {
        EventStore store = createStore();

        store.append(event("s1", "A"));
        store.append(event("s2", "A"));
        store.append(event("s1", "B"));

        assertEquals(List.of(1L, 2L), seqsOf(store, "s1"));
        assertEquals(List.of(1L), seqsOf(store, "s2"));
    }

    @Test
    void historyComesBackOldestFirst() {
        // Drawn top to bottom as the run's timeline. Reversed, a council reads
        // as having synthesised before it generated.
        EventStore store = createStore();
        store.append(event("s1", "GENERATE_STARTED"));
        store.append(event("s1", "REVIEW_STARTED"));
        store.append(event("s1", "SYNTHESIZE_STARTED"));

        assertEquals(List.of("GENERATE_STARTED", "REVIEW_STARTED", "SYNTHESIZE_STARTED"),
                     store.history("s1").stream().map(CouncilEvent::type).toList());
    }

    @Test
    void aStoredEventComesBackFieldForField() {
        EventStore store = createStore();
        CouncilEvent appended = store.append(CouncilEvent.of(
                "s1", "GENERATE", "DRAFT_COMPLETED", "local-llama3",
                Map.of("tokens", 1200, "truncated", false, "note", "a string")));

        CouncilEvent stored = store.history("s1").getFirst();

        assertEquals(appended, stored);
        assertEquals("local-llama3", stored.modelId());
        assertEquals("GENERATE", stored.stage());
        assertEquals(1200, stored.payload().get("tokens"));
        assertEquals(false, stored.payload().get("truncated"));
        assertEquals(appended.occurredAt(), stored.occurredAt());
    }

    @Test
    void aStageLevelEventKeepsItsNullModel() {
        // Rendering "null" as a model name in the timeline would attribute a
        // protocol-level event to a model that was never called.
        EventStore store = createStore();
        store.append(CouncilEvent.of("s1", "PROTOCOL", "PROTOCOL_CANCELLED", null, Map.of()));

        assertNull(store.history("s1").getFirst().modelId());
    }

    @Test
    void anEmptyPayloadStaysEmptyRatherThanBecomingNull() {
        EventStore store = createStore();
        store.append(CouncilEvent.of("s1", "DEBATE", "DEBATE_SKIPPED", null, Map.of()));

        assertEquals(Map.of(), store.history("s1").getFirst().payload());
    }

    @Test
    void anUnknownSessionHasEmptyHistory() {
        assertTrue(createStore().history("never-existed").isEmpty());
    }

    @Test
    void storesAreIndependentOfEachOther() {
        // Positive control for the emptiness assertions above.
        EventStore first = createStore();
        first.append(event("s1", "A"));

        assertTrue(createStore().history("s1").isEmpty(),
                   "each createStore() call must hand back an empty store");
        assertTrue(first.history("s1").size() == 1);
    }

    @Test
    void aLargePayloadIsStoredWhole() {
        // Failure messages carry provider responses, which are not short.
        EventStore store = createStore();
        String detail = "detail ".repeat(20_000);
        store.append(CouncilEvent.of("s1", "GENERATE", "MODEL_CALL_FAILED", "local-llama3",
                                     Map.of("reason", detail)));

        assertEquals(detail, store.history("s1").getFirst().payload().get("reason"));
    }

    /**
     * @param store     the store to read
     * @param sessionId the session to read
     * @return that session's sequences, in history order
     */
    private List<Long> seqsOf(EventStore store, String sessionId) {
        return store.history(sessionId).stream().map(CouncilEvent::seq).toList();
    }

    private CouncilEvent event(String sessionId, String type) {
        return CouncilEvent.of(sessionId, "GENERATE", type, null, Map.of());
    }
}
