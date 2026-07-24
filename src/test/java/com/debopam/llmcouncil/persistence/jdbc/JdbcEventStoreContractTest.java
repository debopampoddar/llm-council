package com.debopam.llmcouncil.persistence.jdbc;

import com.debopam.llmcouncil.application.EventStore;
import com.debopam.llmcouncil.application.EventStoreContractTest;
import com.debopam.llmcouncil.domain.CouncilEvent;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The JDBC event store, held to the same contract as the in-memory one, on a
 * real database file per store.
 *
 * <p>The extra test is the one the in-memory store cannot satisfy, and it checks
 * more than survival: a store reopened on the same file must keep counting from
 * where the sequence had got to. Restarting the count at 1 would give two events
 * the same position, and a cursor at that position would then skip everything
 * written since.
 */
abstract class JdbcEventStoreContractTest extends EventStoreContractTest {

    @TempDir
    private Path tempDir;

    private int storeCount;

    /**
     * @return the engine this subclass exercises
     */
    protected abstract Engine engine();

    @Override
    protected EventStore createStore() {
        return storeOn(newDirectory());
    }

    @Test
    void eventsSurviveTheProcessThatWroteThemAndTheSequenceCarriesOn() {
        Path shared = newDirectory();
        EventStore before = storeOn(shared);
        before.append(CouncilEvent.of("s1", "GENERATE", "FIRST", "local-llama3", Map.of()));
        before.append(CouncilEvent.of("s1", "GENERATE", "SECOND", null, Map.of()));

        EventStore afterRestart = storeOn(shared);
        CouncilEvent third = afterRestart.append(
                CouncilEvent.of("s1", "SYNTHESIZE", "THIRD", null, Map.of()));

        assertEquals(3L, third.seq(), "the sequence resumes rather than restarting at 1");
        assertEquals(List.of("FIRST", "SECOND", "THIRD"),
                     afterRestart.history("s1").stream().map(CouncilEvent::type).toList());
        assertEquals(List.of(1L, 2L, 3L),
                     afterRestart.history("s1").stream().map(CouncilEvent::seq).toList());
    }

    /**
     * Open a store over an existing or new database directory.
     *
     * @param directory where the database file lives
     * @return a store whose schema is migrated
     */
    private EventStore storeOn(Path directory) {
        DataSource dataSource = JdbcTestDatabase.migrated(engine(), directory);
        DocumentMapper documents =
                new DocumentMapper(new ObjectMapper().registerModule(new JavaTimeModule()));
        return new JdbcEventStore(new JdbcTemplate(dataSource), documents);
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
