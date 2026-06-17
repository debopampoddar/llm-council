package com.debopam.llmcouncil.chat;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

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
}
