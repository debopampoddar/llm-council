package com.debopam.llmcouncil.chat;

import com.debopam.llmcouncil.domain.DepthMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The snapshot that lets a {@link ChatSession} be stored.
 *
 * <p>{@code ChatSession} is the only durable type that is not already a record,
 * so it is the only one where storage can lose something. Two losses would be
 * invisible until much later: turns coming back in a different order, which
 * reads as a conversation that happened differently, and {@code createdAt}
 * being restamped on load, which would make every chat look new — so nothing
 * would ever age out of retention, and every chat would jump to the top of a
 * list sorted by recency the moment it was read.
 */
class ChatSessionSnapshotTest {

    private final ObjectMapper objectMapper =
            new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void turnOrderStatusAndSummarySurviveTheRoundTrip() {
        ChatSession original = new ChatSession("c1", "multi-cloud", DepthMode.RIGOROUS, "so far");
        original.addTurn(ChatTurn.running("t1", "first question", "session-1").completed("first"));
        original.addTurn(ChatTurn.running("t2", "second question", "session-2")
                                 .partial("second", "quorum not met"));
        original.addTurn(ChatTurn.running("t3", "third question", "session-3"));

        ChatSession restored = ChatSession.fromSnapshot(original.toSnapshot());

        assertEquals(List.of("t1", "t2", "t3"),
                     restored.turns().stream().map(ChatTurn::id).toList(),
                     "turns come back oldest first, as the conversation happened");
        assertEquals(List.of(ChatTurnStatus.COMPLETED, ChatTurnStatus.PARTIAL,
                             ChatTurnStatus.RUNNING),
                     restored.turns().stream().map(ChatTurn::status).toList());
        assertEquals("so far", restored.summary());
        assertEquals("multi-cloud", restored.profileId());
        assertEquals(DepthMode.RIGOROUS, restored.depthMode());
    }

    @Test
    void createdAtIsNotResetByFromSnapshot() {
        ChatSession original = new ChatSession("c2", "mock", DepthMode.QUICK, "");
        original.addTurn(ChatTurn.running("t1", "question", "session-1"));
        Instant originalCreatedAt = original.createdAt();
        Instant originalUpdatedAt = original.updatedAt();

        ChatSession restored = ChatSession.fromSnapshot(original.toSnapshot());

        assertEquals(originalCreatedAt, restored.createdAt());
        assertEquals(originalUpdatedAt, restored.updatedAt());
        // Control: a chat constructed the ordinary way does stamp itself now, so
        // the equality above is fromSnapshot preserving a value rather than two
        // timestamps that happen to match.
        assertNotEquals(originalCreatedAt,
                        new ChatSession("c3", "mock", DepthMode.QUICK, "").createdAt());
    }

    @Test
    void aRestoredChatStillReportsItsRunningTurn() {
        // hasRunningTurn() is what stops a chat being deleted or evicted while a
        // council is writing into it. If that state did not survive storage, the
        // protection would silently lapse across a restart.
        ChatSession original = new ChatSession("c4", "mock", DepthMode.QUICK, "");
        original.addTurn(ChatTurn.running("t1", "question", "session-1"));

        assertTrue(ChatSession.fromSnapshot(original.toSnapshot()).hasRunningTurn());
    }

    @Test
    void theSnapshotSurvivesJacksonAsWellAsItself() throws Exception {
        // toSnapshot/fromSnapshot on their own would pass even if the record
        // carried a field Jackson could not rebuild. The store writes JSON.
        ChatSession original = new ChatSession("c5", "mock", DepthMode.BALANCED, "summary text");
        original.addTurn(ChatTurn.running("t1", "question", "session-1").completed("answer"));
        original.addTurn(ChatTurn.running("t2", "next", "session-2").failed("model timed out"));

        ChatSession restored = ChatSession.fromSnapshot(
                objectMapper.readValue(objectMapper.writeValueAsString(original.toSnapshot()),
                                       ChatSessionSnapshot.class));

        assertEquals(original.toSnapshot(), restored.toSnapshot());
        assertEquals("model timed out", restored.turn("t2").orElseThrow().failureReason());
        assertEquals(original.createdAt(), restored.createdAt());
    }

    @Test
    void anEmptyChatRoundTripsWithNoTurns() {
        ChatSession restored = ChatSession.fromSnapshot(
                new ChatSession("c6", "mock", DepthMode.QUICK, null).toSnapshot());

        assertTrue(restored.turns().isEmpty());
        assertEquals("", restored.summary(), "a null summary is normalised to empty, not to null");
    }

    @Test
    void aTurnsNullFieldsStayNull() {
        ChatSession original = new ChatSession("c7", "mock", DepthMode.QUICK, "");
        original.addTurn(ChatTurn.running("t1", "question", "session-1"));

        ChatTurn restored = ChatSession.fromSnapshot(original.toSnapshot()).turn("t1").orElseThrow();

        assertNull(restored.assistantAnswer());
        assertNull(restored.failureReason());
    }
}
