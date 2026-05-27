package com.debopam.llmcouncil.application;

import com.debopam.llmcouncil.domain.CouncilEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class InMemoryEventPublisher implements EventPublisher {
    private final ConcurrentHashMap<UUID, List<CouncilEvent>> events = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    @Override
    public CouncilEvent publish(UUID sessionId, String stage, String type, String modelId, Map<String, Object> payload) {
        CouncilEvent event = CouncilEvent.of(sessionId, stage, type, modelId, payload);
        events.computeIfAbsent(sessionId, ignored -> new CopyOnWriteArrayList<>()).add(event);
        for (SseEmitter emitter : emitters.getOrDefault(sessionId, List.of())) {
            send(emitter, event);
        }
        return event;
    }

    @Override
    public List<CouncilEvent> history(UUID sessionId) {
        return List.copyOf(events.getOrDefault(sessionId, List.of()));
    }

    @Override
    public SseEmitter subscribe(UUID sessionId) {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.computeIfAbsent(sessionId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(sessionId, emitter));
        emitter.onTimeout(() -> remove(sessionId, emitter));
        emitter.onError(error -> remove(sessionId, emitter));
        for (CouncilEvent event : history(sessionId)) {
            send(emitter, event);
        }
        return emitter;
    }

    private void send(SseEmitter emitter, CouncilEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .id(event.id().toString())
                    .name(event.type())
                    .data(event));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    private void remove(UUID sessionId, SseEmitter emitter) {
        List<SseEmitter> sessionEmitters = emitters.getOrDefault(sessionId, new ArrayList<>());
        sessionEmitters.remove(emitter);
    }
}
