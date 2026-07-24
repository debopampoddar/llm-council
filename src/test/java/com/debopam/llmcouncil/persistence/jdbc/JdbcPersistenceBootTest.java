package com.debopam.llmcouncil.persistence.jdbc;

import com.debopam.llmcouncil.chat.ChatSession;
import com.debopam.llmcouncil.chat.ChatSessionStore;
import com.debopam.llmcouncil.chat.InMemoryChatSessionStore;
import com.debopam.llmcouncil.domain.CouncilSession;
import com.debopam.llmcouncil.domain.DepthMode;
import com.debopam.llmcouncil.persistence.InMemorySessionStore;
import com.debopam.llmcouncil.persistence.SessionStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The whole application under {@code council.persistence.type=jdbc}.
 *
 * <p>The store contract tests build their databases by hand, which deliberately
 * skips the part of the wiring most likely to be wrong: whether Boot actually
 * runs the Flyway migrations against the datasource this application creates,
 * and whether the JDBC stores replace the in-memory ones rather than joining
 * them. Two beans implementing the same store interface would fail startup, and
 * migrations that never ran would fail on the first query — both at a user's
 * first run with durability switched on, and nowhere in this suite.
 */
@SpringBootTest(properties = {
        "council.persistence.type=jdbc",
        // In-memory H2 is a real database with a real migration to run; it is
        // in-memory only so the test leaves nothing behind.
        "spring.datasource.url=jdbc:h2:mem:jdbc-boot-test;DB_CLOSE_DELAY=-1"
})
class JdbcPersistenceBootTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private SessionStore sessionStore;

    @Autowired
    private ChatSessionStore chatStore;

    @Test
    void theDurableStoresReplaceTheInMemoryOnesRatherThanJoiningThem() {
        assertInstanceOf(JdbcSessionStore.class, sessionStore);
        assertInstanceOf(JdbcChatSessionStore.class, chatStore);
        assertEquals(0, context.getBeanNamesForType(InMemorySessionStore.class).length);
        assertEquals(0, context.getBeanNamesForType(InMemoryChatSessionStore.class).length);
    }

    @Test
    void migrationsRanAgainstTheApplicationsOwnDatasource() {
        // If Flyway had not run, this is where it would show: a save against a
        // table that does not exist. Reading it back proves the round trip
        // through the real ObjectMapper too, not the hand-built one the store
        // contract tests use.
        CouncilSession session = CouncilSession
                .create("boot-session", "Does the schema exist?", null, DepthMode.QUICK, "mock")
                .withFinalAnswer("It does.");
        sessionStore.save(session);

        ChatSession chat = new ChatSession("boot-chat", "mock", DepthMode.QUICK, "summary");
        chatStore.save(chat);

        assertEquals(session, sessionStore.findById("boot-session").orElseThrow());
        assertEquals("summary", chatStore.findById("boot-chat").orElseThrow().summary());
    }
}
