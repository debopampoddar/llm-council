package com.debopam.llmcouncil.application;

import com.debopam.llmcouncil.chat.ChatSession;
import com.debopam.llmcouncil.chat.ChatSessionStore;
import com.debopam.llmcouncil.chat.ChatTurn;
import com.debopam.llmcouncil.chat.ChatTurnStatus;
import com.debopam.llmcouncil.domain.CouncilSession;
import com.debopam.llmcouncil.domain.CouncilStatus;
import com.debopam.llmcouncil.persistence.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Closes out runs the last process died in the middle of.
 *
 * <p>A run does not survive a restart: no queue holds it, no thread resumes it,
 * and the {@code CouncilContext} it was executing against is gone. A session
 * found in {@code RUNNING} status at boot is therefore orphaned by definition,
 * and every one of them is one the previous process was still working on.
 *
 * <p>Without this the UI shows a spinner forever for a run that will never
 * finish, and the API reports {@code RUNNING} for a council that stopped
 * mid-stage. Turning that into an honest error is the whole of the change.
 *
 * <p>Only possible now that sessions are durable. Under
 * {@code council.persistence.type=memory} the store starts empty, so this finds
 * nothing and costs nothing.
 */
@Component
public class InterruptedRunSweeper implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InterruptedRunSweeper.class);

    /** What the user is told, on both the session and the chat turn. */
    public static final String INTERRUPTED_BY_RESTART =
            "Run was interrupted by an application restart";

    private final SessionStore sessions;
    private final ChatSessionStore chats;

    /**
     * @param sessions the session store to sweep
     * @param chats    the chat store, so an orphaned run's turn is closed too
     */
    public InterruptedRunSweeper(SessionStore sessions, ChatSessionStore chats) {
        this.sessions = sessions;
        this.chats = chats;
    }

    @Override
    public void run(ApplicationArguments args) {
        sweep();
    }

    /**
     * Mark every {@code RUNNING} session interrupted, and fail its chat turn.
     *
     * <p>Public and separate from {@link #run} so it can be exercised directly
     * rather than by starting an application context.
     *
     * @return how many sessions were closed out
     */
    public int sweep() {
        List<CouncilSession> orphaned = sessions.findByStatus(CouncilStatus.RUNNING);
        for (CouncilSession session : orphaned) {
            sessions.save(session.withStatus(CouncilStatus.INTERRUPTED)
                                 .withFailureReason(INTERRUPTED_BY_RESTART));
            failOwningTurn(session.id());
            log.warn("Session {} was RUNNING at startup and cannot be resumed; marked INTERRUPTED",
                     session.id());
        }
        if (!orphaned.isEmpty()) {
            log.warn("Marked {} interrupted run(s) at startup", orphaned.size());
        }
        return orphaned.size();
    }

    /**
     * Fail the chat turn an orphaned run was answering.
     *
     * <p>The session and the turn are two records of the same thing, and a turn
     * left {@code RUNNING} keeps its chat undeletable and its spinner turning
     * however honest the session has been made.
     *
     * @param sessionId the orphaned council session
     */
    private void failOwningTurn(String sessionId) {
        for (ChatSession chat : chats.findAll()) {
            for (ChatTurn turn : chat.turns()) {
                if (turn.status() == ChatTurnStatus.RUNNING
                    && sessionId.equals(turn.councilSessionId())) {
                    chat.replaceTurn(turn.failed(INTERRUPTED_BY_RESTART));
                    chats.save(chat);
                    return;
                }
            }
        }
    }
}
