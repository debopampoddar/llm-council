package com.debopam.llmcouncil.chat;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Component
public class ChatEventBroker {
    private final Map<String, List<ChatEvent>> eventsByChat = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<ChatEvent>>> subscribersByChat = new ConcurrentHashMap<>();

    public ChatEvent publish(String chatId, String type, Map<String, Object> payload) {
        ChatEvent event = ChatEvent.of(chatId, type, payload);
        eventsByChat.computeIfAbsent(chatId, ignored -> new CopyOnWriteArrayList<>()).add(event);
        subscribersByChat.getOrDefault(chatId, List.of())
                .forEach(listener -> listener.accept(event));
        return event;
    }

    public List<ChatEvent> history(String chatId) {
        return List.copyOf(eventsByChat.getOrDefault(chatId, new ArrayList<>()));
    }

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
}
