package com.debopam.llmcouncil.chat;

import java.util.Optional;

public interface ChatSessionStore {
    void save(ChatSession session);
    Optional<ChatSession> findById(String chatId);
}
