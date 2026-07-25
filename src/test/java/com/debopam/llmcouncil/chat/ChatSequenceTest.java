package com.debopam.llmcouncil.chat;

import com.debopam.llmcouncil.config.RetentionPolicy;
import com.debopam.llmcouncil.config.RetentionSettings;
import com.debopam.llmcouncil.domain.DepthMode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-chat sequence, and the store behind {@link ChatEventBroker}.
 *
 * <p>This sequence is the whole reason a reconnect cursor is possible. One
 * chat's SSE stream multiplexes three independently-ordered sources — the
 * snapshot, the chat's own events, and one council event log per turn — and
 * {@code Last-Event-ID} carries a single value. Without a shared ordering that
 * value locates a position in whichever source happened to send last and says
 * nothing about the others, so resuming from it would skip events on every
 * source but one.
 *
 * <p>The position is allocated at append time rather than per stream connection,
 * which is the part that is easy to get wrong and impossible to notice on a
 * first connection: a per-connection counter numbers a re-interleaved replay
 * differently every time, so frame N would mean a different event on the
 * reconnect than it did originally.
 */
class ChatSequenceTest {

    @Test
    void positionsStartAtOneAndCountUpWithinAChat() {
        ChatEventBroker broker = new ChatEventBroker();

        List<Long> assigned = List.of(
                broker.publish("c1", "CHAT_CREATED", Map.of()).chatSeq(),
                broker.publish("c1", "TURN_STARTED", Map.of()).chatSeq(),
                broker.publish("c1", "TURN_COMPLETED", Map.of()).chatSeq());

        assertEquals(List.of(1L, 2L, 3L), assigned);
        assertEquals(ChatEvent.UNASSIGNED_SEQ, ChatEvent.of("c1", "NOT_PUBLISHED", Map.of()).chatSeq(),
                     "an event that has not been published carries no position");
    }

    @Test
    void eachChatHasItsOwnSequence() {
        ChatEventBroker broker = new ChatEventBroker();

        broker.publish("c1", "CHAT_CREATED", Map.of());
        broker.publish("c2", "CHAT_CREATED", Map.of());
        broker.publish("c1", "TURN_STARTED", Map.of());

        assertEquals(List.of(1L, 2L), seqsOf(broker, "c1"));
        assertEquals(List.of(1L), seqsOf(broker, "c2"));
    }

    @Test
    void theSameAllocatorIsUsedAcrossConnections() {
        // The failure this rules out: a counter scoped to a stream connection.
        // Both readers below see the same positions because the numbers were
        // assigned when the events were published, not when they were read.
        ChatEventBroker broker = new ChatEventBroker();
        broker.publish("c1", "CHAT_CREATED", Map.of());
        broker.publish("c1", "TURN_STARTED", Map.of());

        List<Long> firstReader = seqsOf(broker, "c1");
        List<Long> secondReader = seqsOf(broker, "c1");

        assertEquals(firstReader, secondReader);
        assertEquals(List.of(1L, 2L), firstReader);
    }

    @Test
    void publishedEventsAreStoredAndReplayedOldestFirst() {
        ChatEventBroker broker = new ChatEventBroker();

        broker.publish("c1", "CHAT_CREATED", Map.of("profileId", "mock"));
        broker.publish("c1", "TURN_STARTED", Map.of("turnId", "t1"));

        assertEquals(List.of("CHAT_CREATED", "TURN_STARTED"),
                     broker.history("c1").stream().map(ChatEvent::type).toList());
        assertEquals("t1", broker.history("c1").getLast().payload().get("turnId"));
    }

    @Test
    void subscribersReceiveTheSequencedEvent() {
        // The frame id the client dedupes and resumes on comes off this event.
        // Delivering the unsequenced copy would give every frame an id of 0.
        ChatEventBroker broker = new ChatEventBroker();
        List<ChatEvent> heard = new ArrayList<>();
        broker.subscribe("c1", heard::add);

        broker.publish("c1", "TURN_STARTED", Map.of());

        assertEquals(1, heard.size());
        assertEquals(1L, heard.getFirst().chatSeq());
    }

    @Test
    void forgettingAChatDropsItsEventsAndItsCounter() {
        // Both, or the map that survives becomes the one structure in the
        // process that still grows forever.
        InMemoryChatEventStore store = new InMemoryChatEventStore();
        ChatEventBroker broker = new ChatEventBroker(store, new InMemoryChatSequenceAllocator());
        broker.publish("c1", "CHAT_CREATED", Map.of());
        broker.publish("keep", "CHAT_CREATED", Map.of());

        broker.forgetChat("c1");

        assertTrue(broker.history("c1").isEmpty());
        assertEquals(1, store.retainedChatCount(), "and only that chat's events went");
        assertEquals(1L, broker.publish("c1", "CHAT_CREATED", Map.of()).chatSeq(),
                     "a chat id reused after deletion starts a fresh sequence");
    }

    @Test
    void aChatWithARunningTurnKeepsItsEventsWhileTheStoreEvictsAroundIt() {
        // The open SSE stream is replaying exactly these events.
        ChatSessionStore chats = new InMemoryChatSessionStore();
        ChatSession live = new ChatSession("live", "mock", DepthMode.QUICK, "");
        live.addTurn(ChatTurn.running("t1", "question", "session-1"));
        chats.save(live);
        chats.save(new ChatSession("done", "mock", DepthMode.QUICK, ""));
        InMemoryChatEventStore store = new InMemoryChatEventStore(
                new RetentionPolicy(new RetentionSettings(1, 90, 2000)), chats);

        store.append(ChatEvent.of("live", "TURN_STARTED", Map.of()).withChatSeq(1));
        store.append(ChatEvent.of("done", "TURN_COMPLETED", Map.of()).withChatSeq(1));
        store.append(ChatEvent.of("newest", "CHAT_CREATED", Map.of()).withChatSeq(1));

        assertTrue(!store.history("live").isEmpty(),
                   "a chat mid-turn keeps its events: the open stream is replaying them");
        assertTrue(store.history("done").isEmpty(),
                   "and the finished chat beside it was evicted on the same write");
    }

    @Test
    void aStoreThatCannotWriteDoesNotTakeTheTurnDownWithIt() {
        ChatEventBroker broker =
                new ChatEventBroker(new UnwritableChatEventStore(), new InMemoryChatSequenceAllocator());
        List<ChatEvent> heard = new ArrayList<>();
        broker.subscribe("c1", heard::add);

        ChatEvent published = broker.publish("c1", "TURN_STARTED", Map.of());

        assertEquals(1, heard.size(), "the live stream still got it");
        assertEquals(1L, published.chatSeq());
    }

    /**
     * @param broker the broker to read through
     * @param chatId the chat to read
     * @return that chat's positions, in history order
     */
    private List<Long> seqsOf(ChatEventBroker broker, String chatId) {
        return broker.history(chatId).stream().map(ChatEvent::chatSeq).toList();
    }

    /** A chat event store that always fails, standing in for a full disk. */
    private static final class UnwritableChatEventStore implements ChatEventStore {

        @Override
        public ChatEvent append(ChatEvent event) {
            throw new IllegalStateException("disk full");
        }

        @Override
        public List<ChatEvent> history(String chatId) {
            return List.of();
        }

        @Override
        public List<ChatEvent> since(String chatId, long chatSeq) {
            return List.of();
        }

        @Override
        public void deleteChat(String chatId) {
        }
    }
}
