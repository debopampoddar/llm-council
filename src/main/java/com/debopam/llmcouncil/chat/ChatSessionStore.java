package com.debopam.llmcouncil.chat;

import java.util.List;
import java.util.Optional;

/**
 * Storage for chat aggregates.
 *
 * <p>The contract stays deliberately small so a durable implementation can
 * replace the in-memory one without touching the chat service.
 */
public interface ChatSessionStore {

    /**
     * Insert or replace a chat.
     *
     * @param session the chat to persist
     */
    void save(ChatSession session);

    /**
     * @param chatId the chat id
     * @return the chat, or empty when no chat has that id
     */
    Optional<ChatSession> findById(String chatId);

    /**
     * List every chat, most recently updated first.
     *
     * @return all stored chats in descending {@code updatedAt} order
     */
    List<ChatSession> findAll();

    /**
     * Remove a chat and its turns.
     *
     * @param chatId the chat id
     * @return {@code true} if a chat was removed, {@code false} if none existed
     */
    boolean delete(String chatId);
}
