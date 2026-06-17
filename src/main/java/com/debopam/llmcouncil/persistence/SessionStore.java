package com.debopam.llmcouncil.persistence;
import com.debopam.llmcouncil.domain.CouncilSession;
import java.util.Optional;
public interface SessionStore {
    void save(CouncilSession session);
    Optional<CouncilSession> findById(String sessionId);
}
