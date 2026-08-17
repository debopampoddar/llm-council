package com.debopam.llmcouncil.application;

import com.debopam.llmcouncil.persistence.ArtifactStore;
import com.debopam.llmcouncil.persistence.SessionStore;
import org.springframework.stereotype.Service;

/** Deletes every retained representation of one completed council run. */
@Service
public class CouncilSessionCleanup {
    private final SessionStore sessions;
    private final EventStore events;
    private final ArtifactStore artifacts;
    private final RunResultStore results;

    public CouncilSessionCleanup(SessionStore sessions, EventStore events,
                                 ArtifactStore artifacts, RunResultStore results) {
        this.sessions = sessions;
        this.events = events;
        this.artifacts = artifacts;
        this.results = results;
    }

    /**
     * Remove children before the session. If a store fails, the still-visible
     * session makes the incomplete deletion discoverable and retryable.
     */
    public void delete(String sessionId) {
        events.deleteSession(sessionId);
        artifacts.deleteSession(sessionId);
        results.delete(sessionId);
        sessions.delete(sessionId);
    }
}
