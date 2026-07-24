package com.debopam.llmcouncil.persistence.jdbc;

import com.debopam.llmcouncil.chat.ChatSession;
import com.debopam.llmcouncil.chat.ChatSessionStore;
import com.debopam.llmcouncil.chat.ChatSessionStoreContractTest;
import com.debopam.llmcouncil.chat.ChatTurn;
import com.debopam.llmcouncil.domain.DepthMode;
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

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The JDBC chat store, held to the same contract as the in-memory one, on a real
 * database file per store.
 *
 * <p>The extra test is the one the plan's "done when" is written against: a chat
 * with three turns survives the process that wrote it and still appears in the
 * listing.
 */
abstract class JdbcChatSessionStoreContractTest extends ChatSessionStoreContractTest {

    @TempDir
    private Path tempDir;

    private int storeCount;

    /**
     * @return the engine this subclass exercises
     */
    protected abstract Engine engine();

    @Override
    protected ChatSessionStore createStore() {
        return storeOn(newDirectory());
    }

    @Test
    void aChatWithThreeTurnsSurvivesTheProcessThatWroteIt() {
        Path shared = newDirectory();
        ChatSession chat = new ChatSession("survivor", "mock", DepthMode.BALANCED, "the summary");
        chat.addTurn(ChatTurn.running("t1", "first", "session-1").completed("one"));
        chat.addTurn(ChatTurn.running("t2", "second", "session-2").partial("two", "quorum"));
        chat.addTurn(ChatTurn.running("t3", "third", "session-3").failed("model timed out"));
        storeOn(shared).save(chat);

        // A second store over the same file, holding none of the first one's
        // state: this is what a restart looks like from the store's side.
        ChatSessionStore afterRestart = storeOn(shared);

        ChatSession found = afterRestart.findById("survivor").orElseThrow();
        assertEquals(List.of("t1", "t2", "t3"),
                     found.turns().stream().map(ChatTurn::id).toList());
        assertEquals("one", found.turn("t1").orElseThrow().assistantAnswer());
        assertEquals("model timed out", found.turn("t3").orElseThrow().failureReason());
        assertEquals("the summary", found.summary());
        assertEquals(chat.createdAt(), found.createdAt());
        assertEquals(List.of("survivor"),
                     afterRestart.findAll().stream().map(ChatSession::id).toList());
    }

    /**
     * Open a store over an existing or new database directory.
     *
     * @param directory where the database file lives
     * @return a store whose schema is migrated
     */
    private ChatSessionStore storeOn(Path directory) {
        DataSource dataSource = JdbcTestDatabase.migrated(engine(), directory);
        DocumentMapper documents =
                new DocumentMapper(new ObjectMapper().registerModule(new JavaTimeModule()));
        return new JdbcChatSessionStore(new JdbcTemplate(dataSource), dataSource, documents);
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
