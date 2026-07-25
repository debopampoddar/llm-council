package com.debopam.llmcouncil.chat;

import com.debopam.llmcouncil.application.ChatAttribution;
import com.debopam.llmcouncil.application.ChatPosition;
import com.debopam.llmcouncil.application.DefaultEventPublisher;
import com.debopam.llmcouncil.application.EventPublisher;
import com.debopam.llmcouncil.application.InMemoryEventBroker;
import com.debopam.llmcouncil.application.InMemoryEventStore;
import com.debopam.llmcouncil.domain.CouncilEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Numbering a council event in the sequence of the chat that owns it.
 *
 * <p>This is the last piece the reconnect cursor needs. A chat's stream carries
 * the chat's own events and the council events of each turn, and a cursor over
 * only one of them would resume mid-run having skipped the other — the visible
 * result being a timeline that comes back missing its stages, or a chat that
 * comes back missing its turns.
 *
 * <p>The other half is what must <em>not</em> happen: a council session created
 * directly, with no chat around it, must carry no chat attribution at all.
 * Giving those events a chat id would put a direct run's events into some chat's
 * cursor results.
 */
class ChatTurnAttributionTest {

    @Test
    void aLinkedSessionsEventsAreNumberedInTheChatsSequence() {
        ChatTurnAttribution attribution = new ChatTurnAttribution(new InMemoryChatSequenceAllocator());
        attribution.link("session-1", "c1");
        EventPublisher publisher = publisherWith(attribution);

        CouncilEvent first = publisher.publish("session-1", "GENERATE", "STAGE_STARTED", null, Map.of());
        CouncilEvent second = publisher.publish("session-1", "GENERATE", "STAGE_COMPLETED", null, Map.of());

        assertEquals("c1", first.chatId());
        assertEquals(List.of(1L, 2L), List.of(first.chatSeq(), second.chatSeq()));
    }

    @Test
    void oneSequenceSpansTheChatsOwnEventsAndItsTurnsCouncilEvents() {
        // The whole point. Interleaved sources, one ordering, so a single
        // integer locates a position across all of them.
        ChatSequenceAllocator sequences = new InMemoryChatSequenceAllocator();
        ChatTurnAttribution attribution = new ChatTurnAttribution(sequences);
        ChatEventBroker chatEvents = new ChatEventBroker(new InMemoryChatEventStore(), sequences);
        EventPublisher councilEvents = publisherWith(attribution);
        attribution.link("session-1", "c1");

        long chatCreated = chatEvents.publish("c1", "CHAT_CREATED", Map.of()).chatSeq();
        long turnStarted = chatEvents.publish("c1", "TURN_STARTED", Map.of()).chatSeq();
        long generate = councilEvents.publish("session-1", "GENERATE", "STAGE_STARTED", null, Map.of())
                                     .chatSeq();
        long turnDone = chatEvents.publish("c1", "TURN_COMPLETED", Map.of()).chatSeq();

        assertEquals(List.of(1L, 2L, 3L, 4L), List.of(chatCreated, turnStarted, generate, turnDone),
                     "every source draws from the same counter, in publication order");
    }

    @Test
    void anUnlinkedSessionCarriesNoChatAttribution() {
        // The direct POST /sessions then /run path. Attributing these to a chat
        // would put a direct run's events into that chat's cursor results.
        EventPublisher publisher =
                publisherWith(new ChatTurnAttribution(new InMemoryChatSequenceAllocator()));

        CouncilEvent event = publisher.publish("direct-session", "GENERATE", "STAGE_STARTED",
                                               null, Map.of());

        assertNull(event.chatId());
        assertEquals(CouncilEvent.UNASSIGNED_SEQ, event.chatSeq());
    }

    @Test
    void unlinkingStopsFurtherAttribution() {
        // Control for the assertion above: attribution is capable of firing, so
        // its absence there is the missing link rather than a code path that
        // never runs.
        ChatTurnAttribution attribution = new ChatTurnAttribution(new InMemoryChatSequenceAllocator());
        EventPublisher publisher = publisherWith(attribution);
        attribution.link("session-1", "c1");

        CouncilEvent whileLinked =
                publisher.publish("session-1", "GENERATE", "STAGE_STARTED", null, Map.of());
        attribution.unlink("session-1");
        CouncilEvent afterUnlink =
                publisher.publish("session-1", "EXPORT", "STAGE_COMPLETED", null, Map.of());

        assertEquals("c1", whileLinked.chatId());
        assertNull(afterUnlink.chatId());
    }

    @Test
    void twoChatsRunningAtOnceKeepSeparateSequences() {
        ChatTurnAttribution attribution = new ChatTurnAttribution(new InMemoryChatSequenceAllocator());
        EventPublisher publisher = publisherWith(attribution);
        attribution.link("session-a", "chat-a");
        attribution.link("session-b", "chat-b");

        CouncilEvent firstA = publisher.publish("session-a", "GENERATE", "E", null, Map.of());
        CouncilEvent firstB = publisher.publish("session-b", "GENERATE", "E", null, Map.of());
        CouncilEvent secondA = publisher.publish("session-a", "GENERATE", "E", null, Map.of());

        assertEquals(List.of("chat-a", 1L), List.of(firstA.chatId(), firstA.chatSeq()));
        assertEquals(List.of("chat-b", 1L), List.of(firstB.chatId(), firstB.chatSeq()));
        assertEquals(2L, secondA.chatSeq());
    }

    @Test
    void theSessionSequenceIsUntouchedByChatAttribution() {
        // Two sequences, answering different questions: seq orders one council
        // session's timeline, chatSeq orders the whole chat. Collapsing them
        // would break whichever consumer read the other.
        ChatTurnAttribution attribution = new ChatTurnAttribution(new InMemoryChatSequenceAllocator());
        EventPublisher publisher = publisherWith(attribution);
        // Another chat's traffic pushes the shared counter ahead of this one's.
        attribution.link("noisy", "chat-a");
        publisher.publish("noisy", "GENERATE", "E", null, Map.of());
        publisher.publish("noisy", "GENERATE", "E", null, Map.of());
        attribution.link("quiet", "chat-a");

        CouncilEvent event = publisher.publish("quiet", "GENERATE", "E", null, Map.of());

        assertEquals(1L, event.seq(), "first event of its own session");
        assertEquals(3L, event.chatSeq(), "third event of the chat");
    }

    @Test
    void theNoOpAttributionNeverClaimsAChat() {
        assertTrue(ChatAttribution.NONE.nextPositionFor("anything").isEmpty());
    }

    @Test
    void aChatPositionCarriesBothHalves() {
        ChatPosition position = new ChatPosition("c1", 7L);

        assertEquals(Optional.of(position),
                     Optional.of(new ChatPosition(position.chatId(), position.chatSeq())));
    }

    /**
     * @param attribution the attribution under test
     * @return a publisher over in-memory halves using it
     */
    private EventPublisher publisherWith(ChatAttribution attribution) {
        return new DefaultEventPublisher(new InMemoryEventStore(), new InMemoryEventBroker(),
                                         attribution);
    }
}
