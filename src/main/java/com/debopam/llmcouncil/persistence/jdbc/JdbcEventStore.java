package com.debopam.llmcouncil.persistence.jdbc;

import com.debopam.llmcouncil.application.EventStore;
import com.debopam.llmcouncil.domain.CouncilEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Council events in a database table, one JSON document per row.
 *
 * <p>Events are written on the hot path of every stage, so this store is
 * deliberately plain: one insert, no transaction, no batching. Losing the
 * observability record of a run is acceptable; losing the run is not, which is
 * why the publisher above treats a failure here as a warning rather than as an
 * error — see {@code DefaultEventPublisher}.
 */
@Component
@ConditionalOnProperty(name = "council.persistence.type", havingValue = "jdbc")
public class JdbcEventStore implements EventStore {

    /**
     * How many sessions' counters to keep in memory at once.
     *
     * <p>Generous beside the default retention bound of 500 sessions, and
     * evicting an entry costs nothing but one query: a counter is re-seeded from
     * {@code MAX(seq)}, so a session whose entry was dropped mid-run picks up
     * exactly where it left off. What this cap prevents is the map itself
     * becoming the unbounded store that every other one here stopped being.
     */
    private static final int MAX_CACHED_SEQUENCES = 2_000;

    private final JdbcTemplate jdbc;
    private final DocumentMapper documents;

    /**
     * Per-session counters, seeded from the table on first use.
     *
     * <p>Access-ordered and bounded, so the least recently written session's
     * counter is the one dropped. {@code computeIfAbsent} on a synchronized map
     * runs its mapping function at most once per key, which is what makes the
     * seed-then-increment atomic against the virtual threads a council fans out
     * across.
     */
    private final Map<String, AtomicLong> sequences = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, AtomicLong> eldest) {
                    return size() > MAX_CACHED_SEQUENCES;
                }
            });

    /**
     * @param jdbc      the template over the configured datasource
     * @param documents the shared Jackson round trip
     */
    public JdbcEventStore(JdbcTemplate jdbc, DocumentMapper documents) {
        this.jdbc = jdbc;
        this.documents = documents;
    }

    @Override
    public CouncilEvent append(CouncilEvent event) {
        CouncilEvent sequenced = event.withSeq(nextSeq(event.sessionId()));
        jdbc.update("INSERT INTO council_event "
                    + "(id, session_id, occurred_at, seq, stage, type, model_id, document) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    sequenced.id(),
                    sequenced.sessionId(),
                    DocumentMapper.toEpochMillis(sequenced.occurredAt()),
                    sequenced.seq(),
                    sequenced.stage(),
                    sequenced.type(),
                    sequenced.modelId(),
                    documents.toDocument(sequenced));
        return sequenced;
    }

    @Override
    public List<CouncilEvent> history(String sessionId) {
        return jdbc.query("SELECT document FROM council_event WHERE session_id = ? ORDER BY seq",
                          documents.documentRowMapper(CouncilEvent.class),
                          sessionId);
    }

    /**
     * Allocate the next position in a session's sequence.
     *
     * @param sessionId the council session
     * @return the next sequence number, counting from 1
     */
    private long nextSeq(String sessionId) {
        return sequences.computeIfAbsent(sessionId, id -> new AtomicLong(highestStoredSeq(id)))
                        .incrementAndGet();
    }

    /**
     * Read where a session's sequence had got to.
     *
     * <p>Zero for a session with no events, so the first append is 1. This is
     * also what makes the cached counter safe to evict, and what would let a
     * session that somehow outlived a restart keep counting rather than reissue
     * numbers already on disk.
     *
     * @param sessionId the council session
     * @return the highest sequence stored for it
     */
    private long highestStoredSeq(String sessionId) {
        Long highest = jdbc.queryForObject(
                "SELECT COALESCE(MAX(seq), 0) FROM council_event WHERE session_id = ?",
                Long.class, sessionId);
        return highest == null ? 0L : highest;
    }
}
