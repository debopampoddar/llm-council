package com.debopam.llmcouncil.application;

import com.debopam.llmcouncil.chat.ChatSession;
import com.debopam.llmcouncil.chat.ChatSessionStore;
import com.debopam.llmcouncil.chat.ChatTurn;
import com.debopam.llmcouncil.chat.ChatTurnStatus;
import com.debopam.llmcouncil.chat.InMemoryChatSessionStore;
import com.debopam.llmcouncil.domain.CouncilSession;
import com.debopam.llmcouncil.domain.CouncilStatus;
import com.debopam.llmcouncil.domain.DepthMode;
import com.debopam.llmcouncil.persistence.InMemorySessionStore;
import com.debopam.llmcouncil.persistence.SessionStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Closing out runs the last process died in the middle of.
 *
 * <p>The failure this replaces is a silent one: a session left {@code RUNNING}
 * forever, a spinner that never stops, and a chat that cannot be deleted because
 * it believes a turn is still going. Nothing errors and nothing logs, so it
 * reads as a council that is taking a very long time.
 *
 * <p>The other half — that a finished session is left exactly as it was — is
 * what keeps this from being a sweep that rewrites history at every boot.
 */
class InterruptedRunSweeperTest {

    private final SessionStore sessions = new InMemorySessionStore();
    private final ChatSessionStore chats = new InMemoryChatSessionStore();
    private final InterruptedRunSweeper sweeper = new InterruptedRunSweeper(sessions, chats);

    @Test
    void aRunningSessionAtBootBecomesInterrupted() {
        sessions.save(session("orphan", CouncilStatus.RUNNING));

        assertEquals(1, sweeper.sweep());

        CouncilSession swept = sessions.findById("orphan").orElseThrow();
        assertEquals(CouncilStatus.INTERRUPTED, swept.status());
        assertEquals(InterruptedRunSweeper.INTERRUPTED_BY_RESTART, swept.failureReason());
    }

    @Test
    void itsChatTurnIsFailedWithTheSameReason() {
        // The session and the turn are two records of the same thing. A turn
        // left RUNNING keeps its chat undeletable and its spinner turning
        // however honest the session has been made.
        sessions.save(session("orphan", CouncilStatus.RUNNING));
        ChatSession chat = new ChatSession("c1", "mock", DepthMode.QUICK, "");
        chat.addTurn(ChatTurn.running("t1", "why?", "orphan"));
        chats.save(chat);

        sweeper.sweep();

        ChatTurn turn = chats.findById("c1").orElseThrow().turn("t1").orElseThrow();
        assertEquals(ChatTurnStatus.FAILED, turn.status());
        assertEquals(InterruptedRunSweeper.INTERRUPTED_BY_RESTART, turn.failureReason());
    }

    @Test
    void aFinishedSessionIsLeftAlone() {
        // Otherwise every boot would rewrite the history of every run that ever
        // completed, and a COMPLETED council would come back reporting itself
        // interrupted.
        sessions.save(session("done", CouncilStatus.COMPLETED));
        sessions.save(session("failed", CouncilStatus.FAILED));
        sessions.save(session("cancelled", CouncilStatus.CANCELLED));

        assertEquals(0, sweeper.sweep(), "nothing was RUNNING");

        assertEquals(CouncilStatus.COMPLETED, sessions.findById("done").orElseThrow().status());
        assertEquals(CouncilStatus.FAILED, sessions.findById("failed").orElseThrow().status());
        assertEquals(CouncilStatus.CANCELLED, sessions.findById("cancelled").orElseThrow().status());
    }

    @Test
    void aCreatedSessionIsLeftAloneToo() {
        // CREATED is a session whose run has not started. Nothing was
        // interrupted, and the caller may still run it.
        sessions.save(session("queued", CouncilStatus.CREATED));

        sweeper.sweep();

        assertEquals(CouncilStatus.CREATED, sessions.findById("queued").orElseThrow().status());
    }

    @Test
    void aCompletedTurnOnTheSameChatIsNotDisturbed() {
        // Control: the sweep reaches into chats and changes exactly one turn.
        sessions.save(session("orphan", CouncilStatus.RUNNING));
        ChatSession chat = new ChatSession("c1", "mock", DepthMode.QUICK, "");
        chat.addTurn(ChatTurn.running("t1", "earlier", "earlier-session").completed("an answer"));
        chat.addTurn(ChatTurn.running("t2", "why?", "orphan"));
        chats.save(chat);

        sweeper.sweep();

        ChatSession swept = chats.findById("c1").orElseThrow();
        assertEquals(ChatTurnStatus.COMPLETED, swept.turn("t1").orElseThrow().status());
        assertEquals("an answer", swept.turn("t1").orElseThrow().assistantAnswer());
        assertEquals(ChatTurnStatus.FAILED, swept.turn("t2").orElseThrow().status());
    }

    @Test
    void anOrphanWithNoChatIsStillClosedOut() {
        // The direct POST /sessions then /run path has no chat behind it.
        sessions.save(session("direct", CouncilStatus.RUNNING));

        assertEquals(1, sweeper.sweep());

        assertEquals(CouncilStatus.INTERRUPTED, sessions.findById("direct").orElseThrow().status());
    }

    /**
     * @param id     the session id
     * @param status the status to store it in
     * @return a session dated now, so retention leaves it alone
     */
    private CouncilSession session(String id, CouncilStatus status) {
        return CouncilSession.create(id, "why?", null, DepthMode.QUICK, "mock").withStatus(status);
    }
}
