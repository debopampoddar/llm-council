package com.debopam.llmcouncil.chat;

import com.debopam.llmcouncil.application.ChatAttribution;
import com.debopam.llmcouncil.application.DefaultEventPublisher;
import com.debopam.llmcouncil.application.EventPublisher;
import com.debopam.llmcouncil.application.EventStore;
import com.debopam.llmcouncil.application.InMemoryEventBroker;
import com.debopam.llmcouncil.application.InMemoryEventStore;
import com.debopam.llmcouncil.domain.CouncilEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Resuming a chat's stream from a position.
 *
 * <p>This is the payoff of the shared per-chat sequence. The stream multiplexes
 * the chat's own events and the council events of each turn, and a cursor over
 * only one of them resumes having skipped the other — which a reader sees as a
 * timeline missing its stages, or a chat missing its turns. The interleaving
 * tests below are the ones that would catch a replay built from one source.
 */
class ChatStreamReplayTest {

    private final ChatSequenceAllocator sequences = new InMemoryChatSequenceAllocator();
    private final ChatEventStore chatEvents = new InMemoryChatEventStore();
    private final EventStore councilEvents = new InMemoryEventStore();
    private final ChatTurnAttribution attribution = new ChatTurnAttribution(sequences);
    private final ChatEventBroker broker = new ChatEventBroker(chatEvents, sequences);
    private final EventPublisher publisher =
            new DefaultEventPublisher(councilEvents, new InMemoryEventBroker(), attribution);
    private final ChatStreamReplay replay = new ChatStreamReplay(chatEvents, councilEvents);

    @Test
    void aReplayFromZeroReturnsBothSourcesInOneOrderedRun() {
        attribution.link("session-1", "c1");
        broker.publish("c1", "CHAT_CREATED", Map.of());
        broker.publish("c1", "TURN_STARTED", Map.of());
        publisher.publish("session-1", "GENERATE", "STAGE_STARTED", null, Map.of());
        publisher.publish("session-1", "GENERATE", "STAGE_COMPLETED", null, Map.of());
        broker.publish("c1", "TURN_COMPLETED", Map.of());

        List<ChatStreamFrame> frames = replay.since("c1", 0);

        assertEquals(List.of(1L, 2L, 3L, 4L, 5L),
                     frames.stream().map(ChatStreamFrame::chatSeq).toList());
        assertEquals(List.of("chat", "chat", "council", "council", "chat"),
                     frames.stream().map(ChatStreamFrame::name).toList(),
                     "interleaved by position, not grouped by source");
    }

    @Test
    void aReplayFromAPositionReturnsOnlyWhatFollowed() {
        attribution.link("session-1", "c1");
        broker.publish("c1", "CHAT_CREATED", Map.of());
        broker.publish("c1", "TURN_STARTED", Map.of());
        publisher.publish("session-1", "GENERATE", "STAGE_STARTED", null, Map.of());
        broker.publish("c1", "TURN_COMPLETED", Map.of());

        List<ChatStreamFrame> frames = replay.since("c1", 2);

        assertEquals(List.of(3L, 4L), frames.stream().map(ChatStreamFrame::chatSeq).toList());
        assertEquals(List.of("council", "chat"), frames.stream().map(ChatStreamFrame::name).toList(),
                     "a cursor that only understood one source would drop the other");
    }

    @Test
    void aCursorAtTheEndReturnsNothing() {
        // Positive control for the assertions above: the replay is capable of
        // returning nothing, so the frames they see are the cursor working
        // rather than a method that always returns everything.
        attribution.link("session-1", "c1");
        broker.publish("c1", "CHAT_CREATED", Map.of());
        publisher.publish("session-1", "GENERATE", "STAGE_STARTED", null, Map.of());

        assertTrue(replay.since("c1", 2).isEmpty());
        assertEquals(1, replay.since("c1", 1).size());
    }

    @Test
    void framesCarryTheEventItselfSoTheClientSeesNoNewShape() {
        attribution.link("session-1", "c1");
        broker.publish("c1", "TURN_STARTED", Map.of("turnId", "t1"));
        publisher.publish("session-1", "GENERATE", "STAGE_STARTED", "model-a", Map.of());

        List<ChatStreamFrame> frames = replay.since("c1", 0);

        assertInstanceOf(ChatEvent.class, frames.getFirst().data());
        assertInstanceOf(CouncilEvent.class, frames.getLast().data());
        assertEquals("model-a", ((CouncilEvent) frames.getLast().data()).modelId());
    }

    @Test
    void anotherChatsEventsAreNeverReturned() {
        attribution.link("session-1", "c1");
        attribution.link("session-2", "c2");
        broker.publish("c1", "CHAT_CREATED", Map.of());
        broker.publish("c2", "CHAT_CREATED", Map.of());
        publisher.publish("session-2", "GENERATE", "STAGE_STARTED", null, Map.of());

        List<ChatStreamFrame> frames = replay.since("c1", 0);

        assertEquals(1, frames.size());
        assertEquals("CHAT_CREATED", ((ChatEvent) frames.getFirst().data()).type());
    }

    @Test
    void aDirectRunsEventsBelongToNoChatsReplay() {
        // The POST /sessions then /run path. Those events have no position in
        // any chat's sequence, and returning them would put one user's direct
        // run into another's chat stream.
        broker.publish("c1", "CHAT_CREATED", Map.of());
        publisher.publish("direct-session", "GENERATE", "STAGE_STARTED", null, Map.of());

        List<ChatStreamFrame> frames = replay.since("c1", 0);

        assertEquals(1, frames.size());
        assertInstanceOf(ChatEvent.class, frames.getFirst().data());
    }

    @Test
    void anUnknownChatReplaysNothing() {
        assertTrue(replay.since("never-existed", 0).isEmpty());
    }
}
