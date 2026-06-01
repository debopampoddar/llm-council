package com.debopam.llmcouncil.persistence;

import com.debopam.llmcouncil.domain.CouncilSession;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemorySessionStore implements SessionStore {
    private final ConcurrentHashMap<UUID, CouncilSession> sessions = new ConcurrentHashMap<>();

    @Override
    public CouncilSession save(CouncilSession session) {
        sessions.put(session.id(), session);
        return session;
    }

    @Override
    public Optional<CouncilSession> findById(UUID sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }
}
