package com.debopam.llmcouncil.persistence;
import com.debopam.llmcouncil.domain.CouncilSession;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
@Component
public class InMemorySessionStore implements SessionStore {
    private final Map<String, CouncilSession> store = new ConcurrentHashMap<>();
    @Override public void save(CouncilSession session) { store.put(session.id(), session); }
    @Override public Optional<CouncilSession> findById(String id) { return Optional.ofNullable(store.get(id)); }
}
