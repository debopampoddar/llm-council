package com.debopam.llmcouncil.persistence.jdbc;

import com.debopam.llmcouncil.persistence.jdbc.JdbcTestDatabase.Engine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The shipped migrations, run against both engines.
 *
 * <p>The DDL is the only engine-specific part of this design, so it is the part
 * worth checking on both. A script that happens to be valid H2 and invalid
 * SQLite would fail at first boot on the recommended engine and nowhere else.
 *
 * <p>Timestamps are stored as epoch milliseconds so that {@code ORDER BY} and
 * the retention sweep's age comparison behave identically on both engines. The
 * timestamp test reads the stored value back bit-for-bit rather than only
 * checking the sort order: two writes in the same second sort correctly under
 * almost any column type, so an order assertion alone would pass against a
 * column that had quietly dropped the milliseconds.
 */
class SchemaMigrationTest {

    @ParameterizedTest
    @EnumSource(Engine.class)
    void migrationsCreateBothTablesOnBothEngines(Engine engine, @TempDir Path tempDir) {
        JdbcTemplate jdbc = JdbcTestDatabase.migratedTemplate(engine, tempDir);

        assertEquals(0, count(jdbc, "council_session"));
        assertEquals(0, count(jdbc, "chat_session"));
    }

    @ParameterizedTest
    @EnumSource(Engine.class)
    void migrationsAreIdempotentAcrossRestarts(Engine engine, @TempDir Path tempDir) {
        JdbcTestDatabase.migrated(engine, tempDir);
        // Second boot on the same file: Flyway must find its own history and do
        // nothing rather than re-run V1 and fail on an existing table.
        JdbcTemplate jdbc = JdbcTestDatabase.migratedTemplate(engine, tempDir);

        assertEquals(0, count(jdbc, "council_session"));
    }

    @ParameterizedTest
    @EnumSource(Engine.class)
    void documentColumnsHoldMoreThanAShortString(Engine engine, @TempDir Path tempDir) {
        // A council answer is thousands of characters. A column typed as
        // VARCHAR(255) somewhere would truncate or reject the first real run
        // rather than any test fixture.
        JdbcTemplate jdbc = JdbcTestDatabase.migratedTemplate(engine, tempDir);
        String large = "x".repeat(200_000);

        insertSession(jdbc, "big", 1L, large);

        assertEquals(large.length(),
                     jdbc.queryForObject("SELECT document FROM council_session WHERE id = 'big'",
                                         String.class).length());
    }

    @ParameterizedTest
    @EnumSource(Engine.class)
    void timestampsSurviveTheColumnUnchangedAndOrderChronologically(Engine engine,
                                                                    @TempDir Path tempDir) {
        JdbcTemplate jdbc = JdbcTestDatabase.migratedTemplate(engine, tempDir);
        // Two of these fall inside the same wall-clock second and one is years
        // away. Reading the value back bit-for-bit is what a TIMESTAMP column
        // would not survive: SQLite has no such type and stores whatever the
        // driver hands it, so the sub-second part is exactly what goes missing,
        // collapsing "early" and "later" into the same instant. Under
        // virtual-thread fan-out most events in a run share a second.
        Instant early = Instant.parse("2024-01-01T00:00:00.500Z");
        Instant later = Instant.parse("2024-01-01T00:00:00.900Z");
        Instant latest = Instant.parse("2031-06-01T00:00:00Z");

        insertSession(jdbc, "latest", DocumentMapper.toEpochMillis(latest), "{}");
        insertSession(jdbc, "early", DocumentMapper.toEpochMillis(early), "{}");
        insertSession(jdbc, "later", DocumentMapper.toEpochMillis(later), "{}");

        assertEquals(List.of("early", "later", "latest"),
                     jdbc.queryForList("SELECT id FROM council_session ORDER BY updated_at",
                                       String.class));
        assertEquals(List.of(DocumentMapper.toEpochMillis(early),
                             DocumentMapper.toEpochMillis(later),
                             DocumentMapper.toEpochMillis(latest)),
                     jdbc.queryForList("SELECT updated_at FROM council_session ORDER BY updated_at",
                                       Long.class),
                     "the stored value is the epoch milli that went in, not a rounded one");
    }

    private void insertSession(JdbcTemplate jdbc, String id, long updatedAt, String document) {
        jdbc.update("INSERT INTO council_session "
                    + "(id, profile_id, status, created_at, updated_at, document) "
                    + "VALUES (?, ?, ?, ?, ?, ?)",
                    id, "mock", "COMPLETED", updatedAt, updatedAt, document);
    }

    private int count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
