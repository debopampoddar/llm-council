package com.debopam.llmcouncil.chat;

import com.debopam.llmcouncil.config.CouncilCatalogHolder;
import com.debopam.llmcouncil.config.RetentionPolicy;
import com.debopam.llmcouncil.config.RetentionSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local chat store.
 *
 * <p>Chats do not survive a restart. This is the default store;
 * {@code council.persistence.type=jdbc} replaces it with
 * {@link com.debopam.llmcouncil.persistence.jdbc.JdbcChatSessionStore}, which is
 * held to the same contract test.
 *
 * <p>Chats are evicted oldest-first past the size or age bound, except one with
 * a turn still running. A chat is what the user sees in the sidebar, so this is
 * the store whose eviction is most visible: dropping the chat a run is currently
 * writing into would make that run's answer arrive with nowhere to land.
 */
@Component
@ConditionalOnProperty(name = "council.persistence.type", havingValue = "memory",
                       matchIfMissing = true)
public class InMemoryChatSessionStore implements ChatSessionStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryChatSessionStore.class);

    private final Map<String, ChatSession> sessions = new ConcurrentHashMap<>();
    private final RetentionPolicy retention;

    /**
     * @param catalogHolder supplies the resolved retention bounds, so a user
     *                      overlay's {@code retention} section takes effect
     */
    @Autowired
    public InMemoryChatSessionStore(CouncilCatalogHolder catalogHolder) {
        this(new RetentionPolicy(catalogHolder.get().runtime().retention()));
    }

    /**
     * Direct construction with explicit bounds, for tests.
     *
     * @param retention the bounds to enforce
     */
    public InMemoryChatSessionStore(RetentionPolicy retention) {
        this.retention = retention;
    }

    /** Direct construction at the shipped bounds, for tests that do not care about retention. */
    public InMemoryChatSessionStore() {
        this(new RetentionPolicy(RetentionSettings.DEFAULTS));
    }

    @Override
    public void save(ChatSession session) {
        sessions.put(session.id(), session);
        evict();
    }

    @Override
    public Optional<ChatSession> findById(String chatId) {
        return Optional.ofNullable(sessions.get(chatId));
    }

    /**
     * {@inheritDoc}
     *
     * <p>The {@code id} tiebreaker matches the durable store, which orders by a
     * millisecond-resolution column and so sees ties the map's full-precision
     * instants would not. Without it the two implementations would disagree
     * about chats touched in the same millisecond, and the sidebar would reorder
     * between reads for no reason the user could see.
     */
    @Override
    public List<ChatSession> findAll() {
        return sessions.values().stream()
                       .sorted(Comparator.comparing(ChatSession::updatedAt)
                                         .thenComparing(ChatSession::id)
                                         .reversed())
                       .toList();
    }

    @Override
    public boolean delete(String chatId) {
        return sessions.remove(chatId) != null;
    }

    /** @return how many chats are currently retained */
    public int retainedCount() {
        return sessions.size();
    }

    private void evict() {
        List<RetentionPolicy.Candidate<String>> candidates = sessions.values().stream()
                .map(session -> new RetentionPolicy.Candidate<>(
                        session.id(), session.updatedAt(), session.hasRunningTurn()))
                .toList();

        for (String chatId : retention.selectEvictions(candidates, Instant.now())) {
            sessions.remove(chatId);
            log.debug("Evicted chat {} under retention bounds", chatId);
        }
    }
}
