package com.debopam.llmcouncil.application;

import com.debopam.llmcouncil.api.dto.CouncilRunResponse;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link RunResultStore}, matching the rest of the Chat V1 layer.
 *
 * <p>Results are held until restart and grow with the number of runs, exactly
 * like {@code InMemorySessionStore} and the event history. That is a deliberate
 * limitation of the demo-grade persistence tier, not an oversight; durable
 * storage and retention arrive with Phase 2A.
 */
@Component
public class InMemoryRunResultStore implements RunResultStore {

    private final Map<String, CouncilRunResponse> store = new ConcurrentHashMap<>();

    @Override
    public void save(String sessionId, CouncilRunResponse result) {
        store.put(sessionId, result);
    }

    @Override
    public Optional<CouncilRunResponse> findById(String sessionId) {
        return Optional.ofNullable(store.get(sessionId));
    }
}
