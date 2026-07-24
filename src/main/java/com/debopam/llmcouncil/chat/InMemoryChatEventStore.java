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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory chat event history.
 *
 * <p>Evicted on the same terms as the other in-memory stores. The map this
 * replaces — the one inside {@code ChatEventBroker} — was not in the four the
 * retention work bounded and grew for the life of the process. Splitting it out
 * into a store of its own made that visible, so it is bounded here rather than
 * carried forward.
 *
 * <p>A chat with a turn still running is never evicted, whatever the bounds say.
 * Its events are what the open SSE stream is replaying.
 */
@Component
@ConditionalOnProperty(name = "council.persistence.type", havingValue = "memory",
                       matchIfMissing = true)
public class InMemoryChatEventStore implements ChatEventStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryChatEventStore.class);

    private final Map<String, List<ChatEvent>> eventsByChat = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastActivity = new ConcurrentHashMap<>();

    private final RetentionPolicy retention;
    private final ChatSessionStore chats;

    /**
     * @param catalogHolder supplies the resolved retention bounds, so a user
     *                      overlay's {@code retention} section takes effect
     * @param chats         consulted for chats with a turn still running, which
     *                      are never evicted
     */
    @Autowired
    public InMemoryChatEventStore(CouncilCatalogHolder catalogHolder, ChatSessionStore chats) {
        this(new RetentionPolicy(catalogHolder.get().runtime().retention()), chats);
    }

    /**
     * Direct construction with explicit bounds, for tests.
     *
     * @param retention the bounds to enforce
     * @param chats     the chat store, for the running-turn check
     */
    public InMemoryChatEventStore(RetentionPolicy retention, ChatSessionStore chats) {
        this.retention = retention;
        this.chats = chats;
    }

    /** Direct construction at the shipped bounds, for tests that do not care about retention. */
    public InMemoryChatEventStore() {
        this(new RetentionPolicy(RetentionSettings.DEFAULTS), new InMemoryChatSessionStore());
    }

    @Override
    public ChatEvent append(ChatEvent event) {
        List<ChatEvent> events =
                eventsByChat.computeIfAbsent(event.chatId(), ignored -> new CopyOnWriteArrayList<>());
        events.add(event);
        lastActivity.put(event.chatId(), event.occurredAt());
        trim(events);
        evictOldChats();
        return event;
    }

    @Override
    public List<ChatEvent> history(String chatId) {
        return List.copyOf(eventsByChat.getOrDefault(chatId, List.of()));
    }

    @Override
    public List<ChatEvent> since(String chatId, long chatSeq) {
        return history(chatId).stream()
                              .filter(event -> event.chatSeq() > chatSeq)
                              .toList();
    }

    @Override
    public void deleteChat(String chatId) {
        eventsByChat.remove(chatId);
        lastActivity.remove(chatId);
    }

    /** @return how many chats currently have retained history */
    public int retainedChatCount() {
        return eventsByChat.size();
    }

    /**
     * Drop a chat's oldest events once it passes the per-chat cap.
     *
     * <p>Oldest first, so what survives is the recent end of the conversation.
     */
    private void trim(List<ChatEvent> events) {
        int excess = retention.excessEvents(events.size());
        for (int index = 0; index < excess; index++) {
            events.removeFirst();
        }
    }

    /** Evict whole chats past the size or age bound, oldest first. */
    private void evictOldChats() {
        List<RetentionPolicy.Candidate<String>> candidates = eventsByChat.keySet().stream()
                .map(chatId -> new RetentionPolicy.Candidate<>(
                        chatId,
                        lastActivity.getOrDefault(chatId, Instant.EPOCH),
                        hasRunningTurn(chatId)))
                .toList();

        for (String chatId : retention.selectEvictions(candidates, Instant.now())) {
            eventsByChat.remove(chatId);
            lastActivity.remove(chatId);
            log.debug("Evicted event history for chat {} under retention bounds", chatId);
        }
    }

    /**
     * @param chatId the chat to classify
     * @return whether a council is still writing into this chat
     */
    private boolean hasRunningTurn(String chatId) {
        return chats.findById(chatId).map(ChatSession::hasRunningTurn).orElse(false);
    }
}
