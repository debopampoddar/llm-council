package com.debopam.llmcouncil.api;

import com.debopam.llmcouncil.application.EventPublisher;
import com.debopam.llmcouncil.chat.ChatCouncilService;
import com.debopam.llmcouncil.chat.ChatEventBroker;
import com.debopam.llmcouncil.chat.ChatSession;
import com.debopam.llmcouncil.chat.ChatSessionStore;
import com.debopam.llmcouncil.chat.ChatTurn;
import com.debopam.llmcouncil.chat.ChatTurnAttribution;
import com.debopam.llmcouncil.domain.DepthMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The reconnect cursor, over the real endpoint.
 *
 * <p>{@code CLAUDE.md} recorded that this stream "replays full history on
 * connect and honours no cursor". It honours one now, and the failures worth
 * guarding are on both sides of that: replaying too little on a resume loses
 * events silently, and replaying everything anyway makes the cursor look
 * implemented when it does nothing.
 *
 * <p>Frames are read as raw SSE text rather than through a client, because the
 * thing under test is the frame's {@code id:} line — the value a browser echoes
 * back — and a client abstraction would hide exactly that.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SseCursorTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ChatCouncilService chatService;

    @Autowired
    private ChatEventBroker chatEvents;

    @Autowired
    private EventPublisher councilEvents;

    @Autowired
    private ChatTurnAttribution attribution;

    @Autowired
    private ChatSessionStore chatStore;

    @Test
    void framesCarryTheirChatPositionAsTheEventId() throws Exception {
        // The id is the cursor. Emitting the event's UUID instead would give the
        // client something it cannot compare or resume from.
        ChatSession chat = chatService.createChat("mock", DepthMode.QUICK, null);

        String stream = streamOf(chat.id(), null);

        assertTrue(stream.contains("id:1"), "the CHAT_CREATED frame is position 1");
        assertTrue(stream.contains("event:chat"));
    }

    @Test
    void aFirstConnectionWithNoCursorReplaysEverything() {
        ChatSession chat = chatService.createChat("mock", DepthMode.QUICK, null);
        chatEvents.publish(chat.id(), "TURN_STARTED", Map.of("turnId", "t1"));

        String stream = streamOf(chat.id(), null);

        assertTrue(stream.contains("CHAT_CREATED"));
        assertTrue(stream.contains("TURN_STARTED"));
    }

    @Test
    void aCursorReplaysOnlyWhatFollowedIt() {
        ChatSession chat = chatService.createChat("mock", DepthMode.QUICK, null);
        chatEvents.publish(chat.id(), "TURN_STARTED", Map.of("turnId", "t1"));
        chatEvents.publish(chat.id(), "TURN_COMPLETED", Map.of("turnId", "t1"));

        String stream = streamOf(chat.id(), "2");

        assertFalse(stream.contains("CHAT_CREATED"), "position 1, already delivered");
        assertFalse(stream.contains("TURN_STARTED"), "position 2, already delivered");
        assertTrue(stream.contains("TURN_COMPLETED"), "position 3 is what the client is missing");
    }

    @Test
    void aCursorAlsoCoversTheCouncilEventsOfTheChatsTurns() {
        // The point of the shared sequence. A cursor that only understood the
        // chat's own events would resume having skipped every stage of the run,
        // and the timeline would come back with holes in it.
        ChatSession chat = chatService.createChat("mock", DepthMode.QUICK, null);
        attribution.link("cursor-session", chat.id());
        chatEvents.publish(chat.id(), "TURN_STARTED", Map.of("councilSessionId", "cursor-session"));
        councilEvents.publish("cursor-session", "GENERATE", "STAGE_STARTED", null, Map.of());
        councilEvents.publish("cursor-session", "SYNTHESIZE", "STAGE_COMPLETED", null, Map.of());

        String stream = streamOf(chat.id(), "3");

        assertFalse(stream.contains("STAGE_STARTED"), "position 3, already delivered");
        assertTrue(stream.contains("STAGE_COMPLETED"), "position 4 is what the client is missing");
        assertTrue(stream.contains("event:council"));
    }

    @Test
    void aResumingClientIsNotSentTheWholeRunAgainViaTheTurnSubscription() {
        // The subscription to each turn's council session replays that session's
        // history on connect. Left in place on a resume it would redeliver the
        // whole run behind the cursor's back, and the cursor would appear to
        // work while saving nothing.
        ChatSession chat = chatService.createChat("mock", DepthMode.QUICK, null);
        // A real turn on the chat, not just a TURN_STARTED event: the
        // per-session replay this test is about is driven by chat.turns(), so a
        // chat with no turns would never reach it and the test could not fail.
        chat.addTurn(ChatTurn.running("t1", "why?", "replay-session"));
        chatStore.save(chat);
        attribution.link("replay-session", chat.id());
        chatEvents.publish(chat.id(), "TURN_STARTED", Map.of("councilSessionId", "replay-session"));
        councilEvents.publish("replay-session", "GENERATE", "STAGE_STARTED", null, Map.of());

        String stream = streamOf(chat.id(), "3");

        assertEquals(0, occurrences(stream, "STAGE_STARTED"),
                     "everything up to position 3 stays delivered exactly once — on the "
                     + "connection that first sent it");
    }

    @Test
    void anUnparseableCursorReplaysEverythingRatherThanGuessing() {
        // Duplicates the client will dedupe, versus silently skipping the events
        // between here and a guessed position.
        ChatSession chat = chatService.createChat("mock", DepthMode.QUICK, null);
        chatEvents.publish(chat.id(), "TURN_STARTED", Map.of("turnId", "t1"));

        String stream = streamOf(chat.id(), "not-a-number");

        assertTrue(stream.contains("CHAT_CREATED"));
        assertTrue(stream.contains("TURN_STARTED"));
    }

    @Test
    void theSnapshotIsSentOnAResumeToo() {
        // It is a full state replacement, so a reconnecting client gets the
        // chat's current turns without rebuilding them from what it missed.
        ChatSession chat = chatService.createChat("mock", DepthMode.QUICK, null);
        chatEvents.publish(chat.id(), "TURN_STARTED", Map.of("turnId", "t1"));

        assertTrue(streamOf(chat.id(), "2").contains("event:snapshot"));
    }

    /**
     * Open the stream and return everything written before it was closed.
     *
     * @param chatId the chat to stream
     * @param cursor the resume position, or null for a first connection
     * @return the raw SSE text
     */
    private String streamOf(String chatId, String cursor) {
        try {
            var request = get("/api/council/chats/" + chatId + "/events");
            if (cursor != null) {
                request = request.param("lastEventId", cursor);
            }
            var result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
            // Everything replayed on connect is written before the request
            // returns; the emitter then stays open for live events, which this
            // test does not need.
            return result.getResponse().getContentAsString();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to read the chat stream", ex);
        }
    }

    private int occurrences(String haystack, String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }
}
