package com.debopam.llmcouncil.application;

import com.debopam.llmcouncil.api.dto.CouncilRunResponse;
import com.debopam.llmcouncil.config.CouncilCatalogHolder;
import com.debopam.llmcouncil.config.RetentionPolicy;
import com.debopam.llmcouncil.config.RetentionSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link RunResultStore}, matching the rest of the Chat V1 layer.
 *
 * <p>This holds the largest payload of any store in the process — a full
 * {@link CouncilRunResponse} per run, answer text included — and until retention
 * landed it was also the one with no eviction at all, so the biggest entries
 * accumulated for the life of the process.
 *
 * <p>Age is measured from when a result was stored. The store has no read
 * tracking, so a result someone opened this morning and one nobody has looked at
 * since it was written are indistinguishable here; inventing a last-read
 * timestamp to look smarter would be a guess dressed as a measurement.
 */
@Component
public class InMemoryRunResultStore implements RunResultStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryRunResultStore.class);

    private final Map<String, StoredResult> store = new ConcurrentHashMap<>();
    private final RetentionPolicy retention;
    private final RunRegistry runs;

    /**
     * @param catalogHolder supplies the resolved retention bounds, so a user
     *                      overlay's {@code retention} section takes effect
     * @param runs          identifies runs still in flight, which are never evicted
     */
    @Autowired
    public InMemoryRunResultStore(CouncilCatalogHolder catalogHolder, RunRegistry runs) {
        this(new RetentionPolicy(catalogHolder.get().runtime().retention()), runs);
    }

    /**
     * Direct construction with explicit bounds, for tests.
     *
     * @param retention the bounds to enforce
     * @param runs      the registry of in-flight runs
     */
    public InMemoryRunResultStore(RetentionPolicy retention, RunRegistry runs) {
        this.retention = retention;
        this.runs = runs;
    }

    /** Direct construction at the shipped bounds, for tests that do not care about retention. */
    public InMemoryRunResultStore() {
        this(new RetentionPolicy(RetentionSettings.DEFAULTS), new RunRegistry());
    }

    @Override
    public void save(String sessionId, CouncilRunResponse result) {
        store.put(sessionId, new StoredResult(result, Instant.now()));
        evict();
    }

    @Override
    public Optional<CouncilRunResponse> findById(String sessionId) {
        return Optional.ofNullable(store.get(sessionId)).map(StoredResult::result);
    }

    /** @return how many results are currently retained */
    public int retainedCount() {
        return store.size();
    }

    private void evict() {
        List<RetentionPolicy.Candidate<String>> candidates = store.entrySet().stream()
                .map(entry -> new RetentionPolicy.Candidate<>(
                        entry.getKey(),
                        entry.getValue().storedAt(),
                        runs.find(entry.getKey()).isPresent()))
                .toList();

        for (String sessionId : retention.selectEvictions(candidates, Instant.now())) {
            store.remove(sessionId);
            log.debug("Evicted run result for session {} under retention bounds", sessionId);
        }
    }

    /**
     * A stored result and when it arrived.
     *
     * @param result   the run result
     * @param storedAt when it was written, which is what eviction orders by
     */
    private record StoredResult(CouncilRunResponse result, Instant storedAt) {}
}
