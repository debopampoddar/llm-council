package com.debopam.llmcouncil.application;

import com.debopam.llmcouncil.config.CouncilCatalogHolder;
import com.debopam.llmcouncil.config.RetentionPolicy;
import com.debopam.llmcouncil.config.RetentionSettings;
import com.debopam.llmcouncil.domain.CouncilEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory event history.
 *
 * <p>Process-local: events do not survive a restart. The replay contract is
 * what callers depend on, not where the events live.
 *
 * <p><b>Retention.</b> This is the highest-cardinality store in the process —
 * dozens of events per run — and it once grew for the life of the application
 * with no eviction at all. Two caps apply, answering different questions:
 * {@code maxEventsPerSession} bounds one pathological run, while
 * {@code maxSessions} and {@code maxAgeDays} bound the accumulation of ordinary
 * ones. A session whose run is still in flight is never evicted whatever the
 * bounds say — a timeline that loses its earlier stages mid-run does not read as
 * broken, it reads as stages that never happened.
 */
@Component
public class InMemoryEventStore implements EventStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryEventStore.class);

    private final Map<String, List<CouncilEvent>> eventsBySession = new ConcurrentHashMap<>();
    // Last event time per session, so eviction can order by recency without
    // walking every session's event list on every append.
    private final Map<String, Instant> lastActivity = new ConcurrentHashMap<>();
    // Per-session counters. Held separately from the event lists because the
    // per-session cap drops the oldest events: deriving seq from list size
    // would reissue numbers already used, and a cursor would then replay events
    // the client had seen and skip ones it had not.
    private final Map<String, AtomicLong> sequences = new ConcurrentHashMap<>();

    private final RetentionPolicy retention;
    private final RunRegistry runs;

    /**
     * @param catalogHolder supplies the resolved retention bounds, so a user
     *                      overlay's {@code retention} section takes effect
     * @param runs          identifies runs still in flight, which are never evicted
     */
    @Autowired
    public InMemoryEventStore(CouncilCatalogHolder catalogHolder, RunRegistry runs) {
        this(new RetentionPolicy(catalogHolder.get().runtime().retention()), runs);
    }

    /**
     * Direct construction with explicit bounds, for tests.
     *
     * @param retention the bounds to enforce
     * @param runs      the registry of in-flight runs
     */
    public InMemoryEventStore(RetentionPolicy retention, RunRegistry runs) {
        this.retention = retention;
        this.runs = runs;
    }

    /**
     * Direct construction at the shipped bounds, for tests that do not care
     * about retention.
     *
     * <p>There is deliberately no constructor that switches eviction off:
     * unbounded growth is the defect this replaced, not a mode.
     */
    public InMemoryEventStore() {
        this(new RetentionPolicy(RetentionSettings.DEFAULTS), new RunRegistry());
    }

    @Override
    public CouncilEvent append(CouncilEvent event) {
        CouncilEvent sequenced = event.withSeq(
                sequences.computeIfAbsent(event.sessionId(), ignored -> new AtomicLong())
                         .incrementAndGet());
        List<CouncilEvent> events = eventsBySession.computeIfAbsent(
                event.sessionId(), ignored -> new CopyOnWriteArrayList<>());
        events.add(sequenced);
        lastActivity.put(event.sessionId(), event.occurredAt());
        trim(events);
        evictOldSessions();
        return sequenced;
    }

    @Override
    public List<CouncilEvent> history(String sessionId) {
        return List.copyOf(eventsBySession.getOrDefault(sessionId, new ArrayList<>()));
    }

    /** @return how many sessions currently have retained history */
    public int retainedSessionCount() {
        return eventsBySession.size();
    }

    /**
     * Drop a session's oldest events once it exceeds the per-session cap.
     *
     * <p>Oldest first, so what survives is the end of the run — the stages a
     * reader looking at a finished timeline actually wants. Refusing to record
     * new events instead would truncate the run at the moment it got
     * interesting, and nothing would say so.
     */
    private void trim(List<CouncilEvent> events) {
        int excess = retention.excessEvents(events.size());
        for (int i = 0; i < excess; i++) {
            events.removeFirst();
        }
    }

    /**
     * Evict whole sessions past the size or age bound.
     *
     * <p>Run on every append rather than on a timer. The store is capped at a
     * few hundred entries, so the sweep costs microseconds beside the model call
     * that produced the event, and the bound then holds continuously rather than
     * up to an hour late.
     */
    private void evictOldSessions() {
        List<RetentionPolicy.Candidate<String>> candidates = eventsBySession.keySet().stream()
                .map(sessionId -> new RetentionPolicy.Candidate<>(
                        sessionId,
                        lastActivity.getOrDefault(sessionId, Instant.EPOCH),
                        runs.find(sessionId).isPresent()))
                .toList();

        for (String sessionId : retention.selectEvictions(candidates, Instant.now())) {
            eventsBySession.remove(sessionId);
            lastActivity.remove(sessionId);
            sequences.remove(sessionId);
            log.debug("Evicted event history for session {} under retention bounds", sessionId);
        }
    }
}
