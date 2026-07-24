package com.debopam.llmcouncil.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Sequences a chat event, stores it, then hands it to whoever is streaming.
 *
 * <p>Keeps its name and its method signatures so no caller changes; underneath
 * it is now the same store-plus-broker composition {@code DefaultEventPublisher}
 * uses for council events, for the same reasons. The subscriber map stays here
 * and stays in memory: a subscriber is an open connection held by this process.
 *
 * <p>The position comes from {@link ChatSequenceAllocator} before the event is
 * stored, because that sequence spans this chat's events and the council events
 * of every turn in it — one ordering across the sources the SSE stream
 * multiplexes, which is what makes a reconnect cursor a single integer.
 */
@Component
public class ChatEventBroker {

    private static final Logger log = LoggerFactory.getLogger(ChatEventBroker.class);

    private final Map<String, List<Consumer<ChatEvent>>> subscribersByChat = new ConcurrentHashMap<>();

    private final ChatEventStore store;
    private final ChatSequenceAllocator sequences;

    /**
     * @param store     where chat events are kept and replayed from
     * @param sequences allocates each event's position in its chat
     */
    public ChatEventBroker(ChatEventStore store, ChatSequenceAllocator sequences) {
        this.store = store;
        this.sequences = sequences;
    }

    /**
     * Direct construction over in-memory halves, for tests.
     */
    public ChatEventBroker() {
        this(new InMemoryChatEventStore(), new InMemoryChatSequenceAllocator());
    }

    /**
     * Record and deliver one chat event.
     *
     * <p>A store failure is logged and swallowed, matching the council event
     * path: a turn must not fail because its event log could not be written.
     * The event still reaches subscribers, and comes back carrying the position
     * it was allocated whether or not it was stored.
     *
     * @param chatId  the chat
     * @param type    the event type
     * @param payload structured detail
     * @return the published event
     */
    public ChatEvent publish(String chatId, String type, Map<String, Object> payload) {
        ChatEvent event = ChatEvent.of(chatId, type, payload).withChatSeq(sequences.next(chatId));
        try {
            store.append(event);
        } catch (RuntimeException ex) {
            log.warn("Unable to record chat event {} for chat {}; the turn continues without it: {}",
                     type, chatId, ex.toString());
        }
        subscribersByChat.getOrDefault(chatId, List.of())
                         .forEach(listener -> listener.accept(event));
        return event;
    }

    /**
     * Replay a chat's events, oldest first.
     *
     * @param chatId the chat
     * @return the chat's events
     */
    public List<ChatEvent> history(String chatId) {
        return store.history(chatId);
    }

    /**
     * Subscribe to future events for one chat.
     *
     * @param chatId   the chat to follow
     * @param listener called for each subsequent event
     * @return a handle that unsubscribes when closed
     */
    public AutoCloseable subscribe(String chatId, Consumer<ChatEvent> listener) {
        List<Consumer<ChatEvent>> listeners =
                subscribersByChat.computeIfAbsent(chatId, ignored -> new CopyOnWriteArrayList<>());
        listeners.add(listener);
        return () -> {
            listeners.remove(listener);
            if (listeners.isEmpty()) {
                subscribersByChat.remove(chatId, listeners);
            }
        };
    }

    /**
     * Drop everything held for a deleted chat.
     *
     * <p>Its events and its counter both go. Leaving either behind accumulates
     * state nothing can ever read again, since every read here is scoped to a
     * chat that no longer exists.
     *
     * @param chatId the chat that has been deleted
     */
    public void forgetChat(String chatId) {
        store.deleteChat(chatId);
        sequences.forget(chatId);
    }
}
