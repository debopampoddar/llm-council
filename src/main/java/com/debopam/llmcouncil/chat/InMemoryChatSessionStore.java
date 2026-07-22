package com.debopam.llmcouncil.chat;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local chat store.
 *
 * <p>Chats do not survive a restart. Durable storage is a separate, planned
 * implementation of {@link ChatSessionStore}.
 */
@Component
public class InMemoryChatSessionStore implements ChatSessionStore {

    private final Map<String, ChatSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void save(ChatSession session) {
        sessions.put(session.id(), session);
    }

    @Override
    public Optional<ChatSession> findById(String chatId) {
        return Optional.ofNullable(sessions.get(chatId));
    }

    @Override
    public List<ChatSession> findAll() {
        return sessions.values().stream()
                       .sorted(Comparator.comparing(ChatSession::updatedAt).reversed())
                       .toList();
    }

    @Override
    public boolean delete(String chatId) {
        return sessions.remove(chatId) != null;
    }
}
