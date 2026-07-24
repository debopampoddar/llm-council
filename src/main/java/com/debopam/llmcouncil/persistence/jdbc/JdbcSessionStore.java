package com.debopam.llmcouncil.persistence.jdbc;

import com.debopam.llmcouncil.domain.CouncilSession;
import com.debopam.llmcouncil.domain.CouncilStatus;
import com.debopam.llmcouncil.persistence.SessionStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;

/**
 * Council sessions in a database table, one JSON document per row.
 *
 * <p>Behaviourally identical to the in-memory store — both are checked against
 * the same contract test — with one difference that is the entire point: these
 * rows survive a restart.
 *
 * <p>No transaction spans a run. {@code CouncilService.runCouncil} is save, run,
 * save, and a council run takes minutes; holding a database transaction open
 * across one would pin a connection for the length of a model call and, on
 * SQLite, block every other writer in the process for the same duration. Each
 * save is a single-statement upsert instead.
 */
@Component
@ConditionalOnProperty(name = "council.persistence.type", havingValue = "jdbc")
public class JdbcSessionStore implements SessionStore {

    /** Column order for the upsert, matching the bind order in {@link #save}. */
    private static final List<String> COLUMNS =
            List.of("id", "profile_id", "status", "created_at", "updated_at", "document");

    private final JdbcTemplate jdbc;
    private final DocumentMapper documents;
    private final String upsertSql;

    /**
     * @param jdbc       the template over the configured datasource
     * @param dataSource the same datasource, inspected once for its dialect
     * @param documents  the shared Jackson round trip
     */
    public JdbcSessionStore(JdbcTemplate jdbc, DataSource dataSource, DocumentMapper documents) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.upsertSql = SqlDialect.detect(dataSource).upsert("council_session", "id", COLUMNS);
    }

    @Override
    public void save(CouncilSession session) {
        jdbc.update(upsertSql,
                    session.id(),
                    session.profileId(),
                    session.status().name(),
                    DocumentMapper.toEpochMillis(session.createdAt()),
                    DocumentMapper.toEpochMillis(session.updatedAt()),
                    documents.toDocument(session));
    }

    @Override
    public Optional<CouncilSession> findById(String sessionId) {
        List<CouncilSession> found = jdbc.query(
                "SELECT document FROM council_session WHERE id = ?",
                documents.documentRowMapper(CouncilSession.class),
                sessionId);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.getFirst());
    }

    /**
     * {@inheritDoc}
     *
     * <p>The {@code id} tiebreaker is not cosmetic — {@code updated_at} is
     * stored in milliseconds, so sessions written inside the same millisecond
     * compare equal and would otherwise come back in a different order on each
     * read.
     */
    @Override
    public List<CouncilSession> findAll() {
        return jdbc.query("SELECT document FROM council_session ORDER BY updated_at DESC, id DESC",
                          documents.documentRowMapper(CouncilSession.class));
    }

    @Override
    public List<CouncilSession> findByStatus(CouncilStatus status) {
        return jdbc.query("SELECT document FROM council_session WHERE status = ? "
                          + "ORDER BY updated_at DESC, id DESC",
                          documents.documentRowMapper(CouncilSession.class),
                          status.name());
    }

    @Override
    public boolean delete(String sessionId) {
        return jdbc.update("DELETE FROM council_session WHERE id = ?", sessionId) > 0;
    }
}
