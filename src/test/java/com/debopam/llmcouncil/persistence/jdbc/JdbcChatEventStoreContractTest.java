package com.debopam.llmcouncil.persistence.jdbc;

import com.debopam.llmcouncil.chat.ChatEvent;
import com.debopam.llmcouncil.chat.ChatEventBroker;
import com.debopam.llmcouncil.chat.ChatEventStore;
import com.debopam.llmcouncil.chat.ChatSequenceAllocator;
import com.debopam.llmcouncil.chat.ChatSession;
import com.debopam.llmcouncil.chat.ChatSessionStore;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Durable chat events and the durable position counter behind them.
 *
 * <p>The counter is the reason this file exists. A chat outlives the process it
 * was created in, so a counter that restarted at 1 after a restart would hand
 * new events positions already on disk — and a cursor sitting at one of those
 * positions would replay events the client had seen while skipping ones it had
 * not. Nothing about that is visible until somebody reconnects across a restart.
 */
abstract class JdbcChatEventStoreContractTest {

    @TempDir
    private Path tempDir;

    private int storeCount;

    /**
     * @return the engine this subclass exercises
     */
    protected abstract Engine engine();

    @Test
    void eventsComeBackInPositionOrder() {
        Fixture fixture = fixtureOn(newDirectory());
        fixture.chatExists("c1");

        fixture.broker.publish("c1", "CHAT_CREATED", Map.of("profileId", "mock"));
        fixture.broker.publish("c1", "TURN_STARTED", Map.of("turnId", "t1"));
        fixture.broker.publish("c1", "TURN_COMPLETED", Map.of("turnId", "t1"));

        assertEquals(List.of("CHAT_CREATED", "TURN_STARTED", "TURN_COMPLETED"),
                     fixture.broker.history("c1").stream().map(ChatEvent::type).toList());
        assertEquals(List.of(1L, 2L, 3L),
                     fixture.broker.history("c1").stream().map(ChatEvent::chatSeq).toList());
    }

    @Test
    void aStoredEventComesBackFieldForField() {
        Fixture fixture = fixtureOn(newDirectory());
        fixture.chatExists("c1");

        ChatEvent published =
                fixture.broker.publish("c1", "TURN_FAILED",
                                       Map.of("turnId", "t1", "failureReason", "model timed out"));

        ChatEvent stored = fixture.broker.history("c1").getFirst();
        assertEquals(published, stored);
        assertEquals("model timed out", stored.payload().get("failureReason"));
        assertEquals(published.occurredAt(), stored.occurredAt());
    }

    @Test
    void thePositionCounterCarriesOnAcrossARestart() {
        Path shared = newDirectory();
        Fixture before = fixtureOn(shared);
        before.chatExists("c1");
        before.broker.publish("c1", "CHAT_CREATED", Map.of());
        before.broker.publish("c1", "TURN_STARTED", Map.of());

        // A second set of components over the same file, holding none of the
        // first one's state: this is what a restart looks like.
        Fixture afterRestart = fixtureOn(shared);
        ChatEvent third = afterRestart.broker.publish("c1", "TURN_COMPLETED", Map.of());

        assertEquals(3L, third.chatSeq(), "the sequence resumes rather than restarting at 1");
        assertEquals(List.of(1L, 2L, 3L),
                     afterRestart.broker.history("c1").stream().map(ChatEvent::chatSeq).toList());
    }

    @Test
    void eachChatHasItsOwnSequence() {
        Fixture fixture = fixtureOn(newDirectory());
        fixture.chatExists("c1");
        fixture.chatExists("c2");

        fixture.broker.publish("c1", "CHAT_CREATED", Map.of());
        fixture.broker.publish("c2", "CHAT_CREATED", Map.of());
        fixture.broker.publish("c1", "TURN_STARTED", Map.of());

        assertEquals(List.of(1L, 2L),
                     fixture.broker.history("c1").stream().map(ChatEvent::chatSeq).toList());
        assertEquals(List.of(1L),
                     fixture.broker.history("c2").stream().map(ChatEvent::chatSeq).toList());
    }

    @Test
    void deletingAChatRemovesItsEventsAndOnlyItsEvents() {
        Fixture fixture = fixtureOn(newDirectory());
        fixture.chatExists("drop");
        fixture.chatExists("keep");
        fixture.broker.publish("drop", "CHAT_CREATED", Map.of());
        fixture.broker.publish("keep", "CHAT_CREATED", Map.of());

        fixture.broker.forgetChat("drop");

        assertTrue(fixture.broker.history("drop").isEmpty());
        assertEquals(1, fixture.broker.history("keep").size(),
                     "the chat beside it kept its events");
    }

    @Test
    void allocatingForAChatThatWasNeverSavedFailsLoudly() {
        // Positive control for every allocation above: the allocator is capable
        // of refusing, so the positions it hands out are a row being bumped
        // rather than a method that always returns something.
        Fixture fixture = fixtureOn(newDirectory());

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                                                    () -> fixture.sequences.next("never-saved"));

        assertEquals("No chat never-saved to allocate a sequence position from",
                     thrown.getMessage());
    }

    @Test
    void anAllocatorFailureDoesNotTakeTheTurnDownWithIt() {
        // The broker publishes for a chat that was never saved. The allocation
        // fails, and the turn carries on with an unsequenced event rather than
        // with a position no cursor could trust.
        Fixture fixture = fixtureOn(newDirectory());

        ChatEvent published = fixture.broker.publish("never-saved", "TURN_STARTED", Map.of());

        assertEquals(ChatEvent.UNASSIGNED_SEQ, published.chatSeq());
    }

    /** The components under test, all over one database file. */
    private record Fixture(ChatEventBroker broker,
                           ChatEventStore store,
                           ChatSequenceAllocator sequences,
                           ChatSessionStore chats) {

        /**
         * Save a chat, so its row exists for the allocator to bump.
         *
         * @param chatId the chat to create
         */
        void chatExists(String chatId) {
            chats.save(new ChatSession(chatId, "mock", DepthMode.QUICK, ""));
        }
    }

    /**
     * Build the whole chat-event stack over one database directory.
     *
     * @param directory where the database file lives
     * @return the components, with the schema migrated
     */
    private Fixture fixtureOn(Path directory) {
        DataSource dataSource = JdbcTestDatabase.migrated(engine(), directory);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        DocumentMapper documents =
                new DocumentMapper(new ObjectMapper().registerModule(new JavaTimeModule()));
        ChatEventStore store = new JdbcChatEventStore(jdbc, documents);
        ChatSequenceAllocator sequences = new JdbcChatSequenceAllocator(jdbc);
        return new Fixture(new ChatEventBroker(store, sequences), store, sequences,
                           new JdbcChatSessionStore(jdbc, dataSource, documents));
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
