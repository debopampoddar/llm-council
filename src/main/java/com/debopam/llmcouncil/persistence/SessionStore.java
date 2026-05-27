package com.debopam.llmcouncil.persistence;

import com.debopam.llmcouncil.domain.CouncilSession;

import java.util.Optional;
import java.util.UUID;

public interface SessionStore {
    CouncilSession save(CouncilSession session);
    Optional<CouncilSession> findById(UUID sessionId);
}
