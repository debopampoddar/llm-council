package com.debopam.llmcouncil.persistence;

import com.debopam.llmcouncil.application.EventStore;
import com.debopam.llmcouncil.application.InMemoryEventStore;
import com.debopam.llmcouncil.application.RunRegistry;
import com.debopam.llmcouncil.chat.ChatEventBroker;
import com.debopam.llmcouncil.chat.ChatSession;
import com.debopam.llmcouncil.chat.ChatSessionStore;
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
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scheduled sweep that keeps the durable stores inside their bounds.
 *
 * <p>Two rules carry over from {@link RetentionPolicy} and are worth re-checking
 * here rather than only there, because the policy cannot see whether a caller
 * hands it the right timestamp or marks the right entries protected: oldest
 * first, and never evict something still in use.
 *
 * <p>The third rule is new and belongs only to this class. <b>A session is three
 * things on disk</b> — a row, its events, and an artifact directory that dwarfs
 * both — and deleting the row alone leaves the other two unreachable forever,
 * because every read of them is scoped to a session that no longer exists. The
 * store would look bounded while the disk kept filling, which is precisely the
 * failure durability was supposed to have made visible.
 */
class RetentionServiceTest {

    private final SessionStore sessions = new InMemorySessionStore(unbounded());
    private final EventStore events = new InMemoryEventStore(unbounded(), new RunRegistry());
    private final RecordingArtifactStore artifacts = new RecordingArtifactStore();
    private final ChatSessionStore chats = new InMemoryChatSessionStore(unbounded());
    private final ChatEventBroker chatEvents = new ChatEventBroker();
    private final RunRegistry runs = new RunRegistry();

    @Test
    void sessionsPastTheSizeBoundGoOldestFirst() {
        sessions.save(session("oldest", CouncilStatus.COMPLETED, daysAgo(3)));
        sessions.save(session("middle", CouncilStatus.COMPLETED, daysAgo(2)));
        sessions.save(session("newest", CouncilStatus.COMPLETED, daysAgo(1)));

        assertEquals(1, sweeperKeeping(2).sweep());

        assertTrue(sessions.findById("oldest").isEmpty(), "the least recently updated goes");
        assertTrue(sessions.findById("newest").isPresent());
    }

    @Test
    void aSessionPastTheAgeBoundGoesEvenWithRoomToSpare() {
        sessions.save(session("ancient", CouncilStatus.COMPLETED, daysAgo(120)));
        sessions.save(session("recent", CouncilStatus.COMPLETED, daysAgo(1)));

        sweeperKeeping(1000).sweep();

        assertTrue(sessions.findById("ancient").isEmpty());
        assertTrue(sessions.findById("recent").isPresent());
    }

    @Test
    void evictingASessionTakesItsEventsAndItsArtifactsWithIt() {
        sessions.save(session("doomed", CouncilStatus.COMPLETED, daysAgo(3)));
        sessions.save(session("kept", CouncilStatus.COMPLETED, daysAgo(1)));
        events.append(CouncilEvent.of("doomed", "GENERATE", "E", null, Map.of()));
        events.append(CouncilEvent.of("kept", "GENERATE", "E", null, Map.of()));

        sweeperKeeping(1).sweep();

        assertTrue(events.history("doomed").isEmpty(), "its events went with it");
        assertEquals(List.of("doomed"), artifacts.deleted, "and so did its artifact directory");
        assertFalse(events.history("kept").isEmpty(), "and the session beside it kept both");
    }

    @Test
    void aRunningSessionSurvivesHoweverOldItIs() {
        sessions.save(session("running", CouncilStatus.RUNNING, daysAgo(400)));
        sessions.save(session("finished", CouncilStatus.COMPLETED, daysAgo(399)));

        sweeperKeeping(1).sweep();

        assertTrue(sessions.findById("running").isPresent(),
                   "a run mid-flight would find its own session gone");
        // Control: the sweep did fire, so the survival above is the status rule
        // rather than nothing having happened.
        assertTrue(sessions.findById("finished").isEmpty());
    }

    @Test
    void aCreatedSessionSurvivesToo() {
        sessions.save(session("created", CouncilStatus.CREATED, daysAgo(400)));
        sessions.save(session("failed", CouncilStatus.FAILED, daysAgo(399)));

        sweeperKeeping(1).sweep();

        assertTrue(sessions.findById("created").isPresent());
        assertTrue(sessions.findById("failed").isEmpty());
    }

    @Test
    void aRunInTheRegistrySurvivesEvenWithAFinishedStatus() {
        // The window where the stored status has not caught up with reality is
        // exactly the window in which deleting the session would be worst.
        sessions.save(session("in-flight", CouncilStatus.COMPLETED, daysAgo(400)));
        sessions.save(session("really-done", CouncilStatus.COMPLETED, daysAgo(399)));
        runs.register("in-flight", inFlightContext());

        sweeperKeeping(1).sweep();

        assertTrue(sessions.findById("in-flight").isPresent());
        assertTrue(sessions.findById("really-done").isEmpty());
    }

    @Test
    void anArtifactDirectoryIsNeverDeletedForASessionThatSurvives() {
        // The assertion that would catch a cascade wired to the wrong loop.
        sessions.save(session("kept", CouncilStatus.COMPLETED, daysAgo(1)));

        assertEquals(0, sweeperKeeping(1000).sweep());

        assertTrue(artifacts.deleted.isEmpty());
    }

    @Test
    void chatsPastTheBoundGoAndTakeTheirEventsWithThem() {
        chats.save(chat("oldest", daysAgo(3)));
        chats.save(chat("newest", daysAgo(1)));
        chatEvents.publish("oldest", "CHAT_CREATED", Map.of());
        chatEvents.publish("newest", "CHAT_CREATED", Map.of());

        sweeperKeeping(1).sweep();

        assertTrue(chats.findById("oldest").isEmpty());
        assertTrue(chatEvents.history("oldest").isEmpty(), "its events went with it");
        assertFalse(chatEvents.history("newest").isEmpty());
    }

    @Test
    void aChatWithARunningTurnIsNotEvicted() {
        ChatSession live = chat("live", daysAgo(400));
        live.addTurn(ChatTurn.running("t1", "why?", "session-1"));
        chats.save(live);
        chats.save(chat("done", daysAgo(399)));

        sweeperKeeping(1).sweep();

        assertTrue(chats.findById("live").isPresent(),
                   "a run is still writing into this chat");
        assertTrue(chats.findById("done").isEmpty());
    }

    @Test
    void aSweepOfStoresInsideTheirBoundsRemovesNothing() {
        // Positive control for the whole file: the sweep has to be capable of
        // doing nothing, or every survival assertion above passes for free.
        sessions.save(session("s1", CouncilStatus.COMPLETED, daysAgo(1)));
        chats.save(chat("c1", daysAgo(1)));

        assertEquals(0, sweeperKeeping(100).sweep());

        assertTrue(sessions.findById("s1").isPresent());
        assertTrue(chats.findById("c1").isPresent());
    }

    // ── Fixtures

    /**
     * @param maxSessions how many entries each store may keep
     * @return a service over the shared fixtures at that bound
     */
    private RetentionService sweeperKeeping(int maxSessions) {
        return new RetentionService(sessions, events, artifacts, chats, chatEvents, runs,
                                    new RetentionPolicy(new RetentionSettings(maxSessions, 90, 2000)));
    }

    /** Bounds high enough that the stores' own on-write eviction never fires. */
    private static RetentionPolicy unbounded() {
        return new RetentionPolicy(new RetentionSettings(100_000, 100_000, 100_000));
    }

    private Instant daysAgo(int days) {
        return Instant.now().minus(days, ChronoUnit.DAYS);
    }

    private CouncilSession session(String id, CouncilStatus status, Instant updatedAt) {
        return new CouncilSession(id, "why?", null, DepthMode.QUICK, "mock",
                                  null, null, status, updatedAt, updatedAt, null, null);
    }

    private ChatSession chat(String id, Instant updatedAt) {
        return ChatSession.fromSnapshot(new com.debopam.llmcouncil.chat.ChatSessionSnapshot(
                id, "mock", DepthMode.QUICK, "", List.of(), updatedAt, updatedAt));
    }

    private CouncilContext inFlightContext() {
        return new CouncilContext(
                CouncilSession.create("in-flight", "q", null, DepthMode.QUICK, "mock"),
                new CouncilProfile("mock", "Mock", true, DepthMode.QUICK, Map.of()),
                new CouncilPolicy("p", "proto", List.of("m"), "m", null, 1, 0, false, true),
                new ProtocolDefinition("proto", "proto", List.of(StageType.GENERATE), Map.of()));
    }

    /** Records which session directories the sweep asked to delete. */
    private static final class RecordingArtifactStore implements ArtifactStore {

        private final List<String> deleted = new ArrayList<>();

        @Override
        public boolean deleteSession(String sessionId) {
            deleted.add(sessionId);
            return true;
        }

        @Override
        public void writeText(String sessionId, String relativePath, String text) {
        }

        @Override
        public void writeJson(String sessionId, String relativePath, Object value) {
        }

        @Override
        public List<String> listArtifacts(String sessionId) {
            return List.of();
        }

        @Override
        public Optional<String> readArtifact(String sessionId, String relativePath) {
            return Optional.empty();
        }
    }
}
