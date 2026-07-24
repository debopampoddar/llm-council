package com.debopam.llmcouncil.persistence;

import com.debopam.llmcouncil.domain.CouncilSession;
import com.debopam.llmcouncil.domain.CouncilStatus;

import java.util.List;
import java.util.Optional;

/**
 * Storage for council sessions.
 *
 * <p>The contract stays deliberately small so a durable implementation can
 * replace the in-memory one without touching orchestration.
 */
public interface SessionStore {

    /**
     * Insert or replace a session.
     *
     * @param session the session to persist
     */
    void save(CouncilSession session);

    /**
     * @param sessionId the session id
     * @return the session, or empty when none has that id
     */
    Optional<CouncilSession> findById(String sessionId);

    /**
     * Every stored session, most recently updated first.
     *
     * <p>Read by the retention sweep, which needs each session's age and status
     * to decide what may go. Bounded in practice by that same sweep.
     *
     * @return all stored sessions in descending {@code updatedAt} order
     */
    List<CouncilSession> findAll();

    /**
     * Sessions in one lifecycle state.
     *
     * <p>Exists for the interrupted-run sweep, which asks for {@code RUNNING}
     * at boot, and is why the durable schema indexes {@code status}.
     *
     * @param status the status to match
     * @return matching sessions, most recently updated first
     */
    List<CouncilSession> findByStatus(CouncilStatus status);

    /**
     * Remove a session.
     *
     * @param sessionId the session id
     * @return {@code true} if a session was removed, {@code false} if none existed
     */
    boolean delete(String sessionId);
}
