package com.debopam.llmcouncil.persistence;

import com.debopam.llmcouncil.config.CouncilCatalogHolder;
import com.debopam.llmcouncil.config.RetentionPolicy;
import com.debopam.llmcouncil.config.RetentionSettings;
import com.debopam.llmcouncil.domain.CouncilSession;
import com.debopam.llmcouncil.domain.CouncilStatus;
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
 * Process-local session store.
 *
 * <p>Sessions are evicted oldest-first once the store passes its size or age
 * bound, with one exception that overrides both: a session in {@code RUNNING} or
 * {@code CREATED} status is never evicted, however old it is. Both statuses mean
 * the session's work has not finished — {@code CREATED} is a session awaiting
 * its run, {@code RUNNING} one in progress — and evicting either would make a
 * live run's own lookup of itself fail with a 404 that reads as "no such
 * session" rather than "your session was deleted out from under you".
 *
 * <p>This store checks status directly rather than asking the run registry,
 * because it is the only one of the four that holds the status itself. A
 * {@code CREATED} session has produced no events and no result, so the other
 * stores have nothing of its to protect.
 */
@Component
public class InMemorySessionStore implements SessionStore {

    private static final Logger log = LoggerFactory.getLogger(InMemorySessionStore.class);

    private final Map<String, CouncilSession> store = new ConcurrentHashMap<>();
    private final RetentionPolicy retention;

    /**
     * @param catalogHolder supplies the resolved retention bounds, so a user
     *                      overlay's {@code retention} section takes effect
     */
    @Autowired
    public InMemorySessionStore(CouncilCatalogHolder catalogHolder) {
        this(new RetentionPolicy(catalogHolder.get().runtime().retention()));
    }

    /**
     * Direct construction with explicit bounds, for tests.
     *
     * @param retention the bounds to enforce
     */
    public InMemorySessionStore(RetentionPolicy retention) {
        this.retention = retention;
    }

    /** Direct construction at the shipped bounds, for tests that do not care about retention. */
    public InMemorySessionStore() {
        this(new RetentionPolicy(RetentionSettings.DEFAULTS));
    }

    @Override
    public void save(CouncilSession session) {
        store.put(session.id(), session);
        evict();
    }

    @Override
    public Optional<CouncilSession> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    /** @return how many sessions are currently retained */
    public int retainedCount() {
        return store.size();
    }

    private void evict() {
        List<RetentionPolicy.Candidate<String>> candidates = store.values().stream()
                .map(session -> new RetentionPolicy.Candidate<>(
                        session.id(), session.updatedAt(), unfinished(session)))
                .toList();

        for (String sessionId : retention.selectEvictions(candidates, Instant.now())) {
            store.remove(sessionId);
            log.debug("Evicted session {} under retention bounds", sessionId);
        }
    }

    /**
     * Whether this session's work is still outstanding.
     *
     * @param session the session to classify
     * @return {@code true} for {@code CREATED} and {@code RUNNING}, which are
     *         never evicted regardless of age
     */
    private boolean unfinished(CouncilSession session) {
        return session.status() == CouncilStatus.RUNNING || session.status() == CouncilStatus.CREATED;
    }
}
