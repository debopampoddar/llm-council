package com.debopam.llmcouncil.persistence.jdbc;

import com.debopam.llmcouncil.domain.CouncilSession;
import com.debopam.llmcouncil.domain.DepthMode;
import com.debopam.llmcouncil.persistence.SessionStore;
import com.debopam.llmcouncil.persistence.SessionStoreContractTest;
import com.debopam.llmcouncil.persistence.jdbc.JdbcTestDatabase.Engine;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The JDBC store, held to the same {@link SessionStoreContractTest} as the
 * in-memory one, on a real database file per store.
 *
 * <p>Concrete subclasses name the engine. Two {@code createStore()} calls never
 * share a database: each gets its own directory, because the contract's
 * independence check calls the factory twice inside one test and a shared file
 * would hand back a store that was supposed to be empty and was not.
 *
 * <p>The extra test here is the one thing the in-memory store cannot satisfy and
 * the whole reason this class exists — a session written by one store is found
 * by a different store opened later on the same file.
 */
abstract class JdbcSessionStoreContractTest extends SessionStoreContractTest {

    @TempDir
    private Path tempDir;

    private int storeCount;

    /**
     * @return the engine this subclass exercises
     */
    protected abstract Engine engine();

    @Override
    protected SessionStore createStore() {
        return storeOn(newDirectory());
    }

    @Test
    void aSessionSurvivesTheProcessThatWroteIt() {
        Path shared = newDirectory();
        CouncilSession session = CouncilSession
                .create("survivor", "Does this outlive a restart?", null, DepthMode.QUICK, "mock")
                .withFinalAnswer("Yes.");
        storeOn(shared).save(session);

        // A second store over the same file, holding none of the first one's
        // state: this is what a restart looks like from the store's side.
        SessionStore afterRestart = storeOn(shared);

        assertEquals(session, afterRestart.findById("survivor").orElseThrow());
    }

    /**
     * Open a store over an existing or new database directory.
     *
     * @param directory where the database file lives
     * @return a store whose schema is migrated
     */
    private SessionStore storeOn(Path directory) {
        DataSource dataSource = JdbcTestDatabase.migrated(engine(), directory);
        DocumentMapper documents =
                new DocumentMapper(new ObjectMapper().registerModule(new JavaTimeModule()));
        return new JdbcSessionStore(new JdbcTemplate(dataSource), dataSource, documents);
    }

    private Path newDirectory() {
        Path directory = tempDir.resolve("store-" + storeCount++);
        try {
            Files.createDirectories(directory);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to create test database directory", ex);
        }
        return directory;
    }
}
