package com.debopam.llmcouncil.application;

import com.debopam.llmcouncil.config.TestModels;
import com.debopam.llmcouncil.api.dto.CouncilRunResponse;
import com.debopam.llmcouncil.chat.ChatSession;
import com.debopam.llmcouncil.chat.ChatTurn;
import com.debopam.llmcouncil.chat.InMemoryChatSessionStore;
import com.debopam.llmcouncil.config.RetentionPolicy;
import com.debopam.llmcouncil.config.RetentionSettings;
import com.debopam.llmcouncil.domain.CouncilEvent;
import com.debopam.llmcouncil.domain.CouncilSession;
import com.debopam.llmcouncil.domain.CouncilStatus;
import com.debopam.llmcouncil.domain.DepthMode;
import com.debopam.llmcouncil.model.CouncilPolicy;
import com.debopam.llmcouncil.model.CouncilProfile;
import com.debopam.llmcouncil.orchestration.CouncilContext;
import com.debopam.llmcouncil.orchestration.ProtocolDefinition;
import com.debopam.llmcouncil.orchestration.StageType;
import com.debopam.llmcouncil.persistence.InMemorySessionStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Eviction in the four in-memory stores (F5).
 *
 * <p>All four were plain {@code ConcurrentHashMap}s that only ever grew, for the
 * life of the process. {@link RetentionPolicy} owns the decision and is tested
 * on its own; this file checks that each store actually asks it, hands it the
 * right timestamp, and marks the right entries as still in use — three things a
 * policy test cannot see.
 *
 * <p>Each "survived" assertion is paired with an eviction in the same call, so a
 * store that had quietly stopped evicting could not pass.
 */
class StoreEvictionTest {

    // ── Sessions

    @Test
    void theSessionStoreEvictsOldestFirstOnceItPassesItsCap() {
        InMemorySessionStore store = new InMemorySessionStore(policy(2, 90));

        store.save(session("oldest", CouncilStatus.COMPLETED, daysAgo(3)));
        store.save(session("middle", CouncilStatus.COMPLETED, daysAgo(2)));
        store.save(session("newest", CouncilStatus.COMPLETED, daysAgo(1)));

        assertEquals(2, store.retainedCount());
        assertTrue(store.findById("oldest").isEmpty(), "the least recently updated session goes");
        assertTrue(store.findById("newest").isPresent());
    }

    @Test
    void aRunningSessionSurvivesEvictionHoweverOldItIs() {
        InMemorySessionStore store = new InMemorySessionStore(policy(1, 90));

        store.save(session("running", CouncilStatus.RUNNING, daysAgo(400)));
        store.save(session("finished", CouncilStatus.COMPLETED, daysAgo(399)));

        assertTrue(store.findById("running").isPresent(),
                   "a RUNNING session is mid-flight; evicting it makes the run's own lookup of "
                   + "itself 404 as though the session had never existed");
        // Control: eviction did fire on that save, so the survival above is the
        // status rule working rather than nothing happening.
        assertTrue(store.findById("finished").isEmpty());
    }

    @Test
    void aCreatedSessionSurvivesEvictionToo() {
        // CREATED is a session whose run has not started. It is as unfinished as
        // RUNNING, and evicting it loses a request the caller is about to run.
        InMemorySessionStore store = new InMemorySessionStore(policy(1, 90));

        store.save(session("created", CouncilStatus.CREATED, daysAgo(400)));
        store.save(session("failed", CouncilStatus.FAILED, daysAgo(399)));

        assertTrue(store.findById("created").isPresent());
        assertTrue(store.findById("failed").isEmpty());
    }

    @Test
    void aFinishedSessionPastTheAgeBoundGoesEvenWithRoomToSpare() {
        InMemorySessionStore store = new InMemorySessionStore(policy(1000, 90));

        store.save(session("ancient", CouncilStatus.COMPLETED, daysAgo(120)));
        store.save(session("recent", CouncilStatus.COMPLETED, daysAgo(1)));

        assertTrue(store.findById("ancient").isEmpty(), "past maxAgeDays with the store nearly empty");
        assertTrue(store.findById("recent").isPresent());
    }

    @Test
    void aSessionStoreWithinItsBoundsKeepsEverything() {
        // Positive control for the whole section.
        InMemorySessionStore store = new InMemorySessionStore(policy(10, 90));

        store.save(session("a", CouncilStatus.COMPLETED, daysAgo(3)));
        store.save(session("b", CouncilStatus.COMPLETED, daysAgo(2)));

        assertEquals(2, store.retainedCount());
    }

    // ── Events
    //
    // Retention lives on the store rather than on the publisher, so these
    // target InMemoryEventStore directly. The publisher above it only appends
    // and fans out.

    @Test
    void aSessionsOldestEventsAreDroppedOnceItPassesThePerSessionCap() {
        InMemoryEventStore events = new InMemoryEventStore(
                new RetentionPolicy(new RetentionSettings(500, 90, 5)), new RunRegistry());

        for (int i = 0; i < 8; i++) {
            events.append(event("s1", "EVENT_" + i));
        }

        assertEquals(5, events.history("s1").size());
        assertEquals("EVENT_3", events.history("s1").getFirst().type(),
                     "the oldest go, so what survives is the end of the run — which is what a "
                     + "reader looking at a finished timeline wants");
        assertEquals("EVENT_7", events.history("s1").getLast().type());
    }

    @Test
    void aRunUnderThePerSessionCapKeepsEveryEvent() {
        // Positive control: the trim has to be capable of not firing.
        InMemoryEventStore events = new InMemoryEventStore(
                new RetentionPolicy(new RetentionSettings(500, 90, 5)), new RunRegistry());

        events.append(event("s1", "ONLY"));

        assertEquals(1, events.history("s1").size());
    }

    @Test
    void wholeSessionsAreEvictedOnceTheEventStorePassesItsCap() {
        InMemoryEventStore events = new InMemoryEventStore(policy(2, 90), new RunRegistry());

        events.append(event("s1", "E"));
        events.append(event("s2", "E"));
        events.append(event("s3", "E"));

        assertEquals(2, events.retainedSessionCount());
        assertTrue(events.history("s1").isEmpty(), "the least recently active session goes");
        assertFalse(events.history("s3").isEmpty());
    }

    @Test
    void anInFlightRunKeepsItsEventHistoryWhileTheStoreEvictsAroundIt() {
        RunRegistry runs = new RunRegistry();
        InMemoryEventStore events = new InMemoryEventStore(policy(1, 90), runs);

        events.append(event("live", "E"));
        runs.register("live", inFlightContext());
        events.append(event("done", "E"));
        events.append(event("newest", "E"));

        assertFalse(events.history("live").isEmpty(),
                    "a run still executing keeps its history: a timeline that loses its earlier "
                    + "stages mid-run reads as stages that never happened");
        assertTrue(events.history("done").isEmpty(),
                   "and the finished session next to it was evicted on the same write");
    }

    // ── Run results

    @Test
    void theRunResultStoreEvictsOldestFirstOnceItPassesItsCap() {
        InMemoryRunResultStore store = new InMemoryRunResultStore(policy(2, 90), new RunRegistry());

        store.save("r1", result("r1"));
        store.save("r2", result("r2"));
        store.save("r3", result("r3"));

        assertEquals(2, store.retainedCount());
        assertTrue(store.findById("r1").isEmpty(), "the first result stored is the first to go");
        assertTrue(store.findById("r3").isPresent());
    }

    @Test
    void anInFlightRunsResultIsNotEvicted() {
        RunRegistry runs = new RunRegistry();
        InMemoryRunResultStore store = new InMemoryRunResultStore(policy(1, 90), runs);

        store.save("live", result("live"));
        runs.register("live", inFlightContext());
        store.save("done", result("done"));
        store.save("newest", result("newest"));

        assertTrue(store.findById("live").isPresent());
        assertTrue(store.findById("done").isEmpty(), "the finished result beside it was evicted");
    }

    @Test
    void aRunResultStoreWithinItsBoundsKeepsEverything() {
        InMemoryRunResultStore store = new InMemoryRunResultStore(policy(10, 90), new RunRegistry());

        store.save("r1", result("r1"));
        store.save("r2", result("r2"));

        assertEquals(2, store.retainedCount());
    }

    // ── Chats

    @Test
    void theChatStoreEvictsOldestFirstOnceItPassesItsCap() {
        InMemoryChatSessionStore store = new InMemoryChatSessionStore(policy(2, 90));

        store.save(chat("c1"));
        store.save(chat("c2"));
        store.save(chat("c3"));

        assertEquals(2, store.retainedCount());
        assertTrue(store.findById("c1").isEmpty());
        assertTrue(store.findById("c3").isPresent());
    }

    @Test
    void aChatWithARunningTurnIsNotEvicted() {
        InMemoryChatSessionStore store = new InMemoryChatSessionStore(policy(1, 90));

        ChatSession live = chat("live");
        live.addTurn(ChatTurn.running("t1", "question", "session-1"));
        store.save(live);
        store.save(chat("done"));
        store.save(chat("newest"));

        assertTrue(store.findById("live").isPresent(),
                   "a run is still writing into this chat; evicting it leaves the answer with "
                   + "nowhere to land");
        assertTrue(store.findById("done").isEmpty());
    }

    @Test
    void aChatStoreWithinItsBoundsKeepsEverything() {
        InMemoryChatSessionStore store = new InMemoryChatSessionStore(policy(10, 90));

        store.save(chat("c1"));
        store.save(chat("c2"));

        assertEquals(2, store.retainedCount());
    }

    // ── Fixtures

    private RetentionPolicy policy(int maxSessions, int maxAgeDays) {
        return new RetentionPolicy(new RetentionSettings(maxSessions, maxAgeDays, 2000));
    }

    private Instant daysAgo(int days) {
        return Instant.now().minus(days, ChronoUnit.DAYS);
    }

    /** A session with an explicit {@code updatedAt}, which is what eviction orders by. */
    private CouncilSession session(String id, CouncilStatus status, Instant updatedAt) {
        return new CouncilSession(id, "question", null, DepthMode.QUICK, "mock",
                                  null, null, status, updatedAt, updatedAt, null, null);
    }

    private CouncilEvent event(String sessionId, String type) {
        return CouncilEvent.of(sessionId, "GENERATE", type, null, Map.of());
    }

    private ChatSession chat(String id) {
        return new ChatSession(id, "mock", DepthMode.QUICK, id);
    }

    private CouncilRunResponse result(String sessionId) {
        return CouncilRunResponse.failed(
                CouncilSession.create(sessionId, "q", null, DepthMode.QUICK, "mock"), "test");
    }

    /** A minimal context, standing in for a run the registry considers in flight. */
    private CouncilContext inFlightContext() {
        return new CouncilContext(
                CouncilSession.create("live", "q", null, DepthMode.QUICK, "mock"),
                TestModels.profile("mock").displayName("Mock").testOnly(true)
                          .defaultDepth(DepthMode.QUICK).build(),
                TestModels.policy("p").protocol("proto").members("m").chair("m").build(),
                new ProtocolDefinition("proto", "proto", List.of(StageType.GENERATE), Map.of()));
    }
}
