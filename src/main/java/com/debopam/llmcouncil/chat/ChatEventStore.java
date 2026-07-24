package com.debopam.llmcouncil.chat;

import java.util.List;

/**
 * Where a chat's own events are kept and replayed from.
 *
 * <p>The same split {@code EventStore} and {@code EventBroker} make for council
 * events, for the same reason and one more. Without a durable implementation of
 * this the stream would be half-durable: a chat and its turns would survive a
 * restart while the event log they are described by would not, so a reconnect
 * cursor would point into a history that no longer existed.
 */
public interface ChatEventStore {

    /**
     * Record an event.
     *
     * @param event the event to keep, already carrying its allocated position
     * @return the stored event
     * @throws RuntimeException if the event could not be stored; the broker
     *                          treats that as an observability loss, never as a
     *                          reason to fail a turn
     */
    ChatEvent append(ChatEvent event);

    /**
     * Replay everything recorded for one chat, oldest first.
     *
     * @param chatId the chat
     * @return the chat's events, empty when there are none
     */
    List<ChatEvent> history(String chatId);

    /**
     * Remove a deleted chat's events.
     *
     * <p>Deleting a chat and leaving its event log behind would accumulate rows
     * nothing can ever read, since every read of this store is scoped to a chat
     * that no longer exists.
     *
     * @param chatId the chat that has been deleted
     */
    void deleteChat(String chatId);
}
