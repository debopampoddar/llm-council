package com.debopam.llmcouncil.api;

import com.debopam.llmcouncil.api.dto.ChatMessageRequest;
import com.debopam.llmcouncil.api.dto.ChatResponse;
import com.debopam.llmcouncil.api.dto.CreateChatRequest;
import com.debopam.llmcouncil.application.EventPublisher;
import com.debopam.llmcouncil.chat.ChatCouncilService;
import com.debopam.llmcouncil.chat.ChatEvent;
import com.debopam.llmcouncil.chat.ChatEventBroker;
import com.debopam.llmcouncil.chat.ChatSession;
import com.debopam.llmcouncil.domain.CouncilEvent;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@RestController
@RequestMapping("/api/council/chats")
public class ChatController {
    private static final long SSE_TIMEOUT_MILLIS = 30L * 60L * 1000L;

    private final ChatCouncilService chatService;
    private final ChatEventBroker chatEvents;
    private final EventPublisher councilEvents;

    public ChatController(ChatCouncilService chatService,
                          ChatEventBroker chatEvents,
                          EventPublisher councilEvents) {
        this.chatService = chatService;
        this.chatEvents = chatEvents;
        this.councilEvents = councilEvents;
    }

    @PostMapping
    public ResponseEntity<ChatResponse> create(@RequestBody @Valid CreateChatRequest request) {
        ChatSession chat = chatService.createChat(
                request.profileId(),
                request.depthMode(),
                request.initialContext());
        return ResponseEntity.status(HttpStatus.CREATED).body(ChatResponse.from(chat));
    }

    @PostMapping("/{chatId}/messages")
    public ResponseEntity<ChatResponse> ask(@PathVariable("chatId") String chatId,
                                            @RequestBody @Valid ChatMessageRequest request) {
        return ResponseEntity.ok(ChatResponse.from(chatService.ask(chatId, request.message())));
    }

    @GetMapping("/{chatId}")
    public ResponseEntity<ChatResponse> get(@PathVariable("chatId") String chatId) {
        return ResponseEntity.ok(ChatResponse.from(chatService.getChat(chatId)));
    }

    @GetMapping(path = "/{chatId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable("chatId") String chatId) {
        ChatSession chat = chatService.getChat(chatId);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        StreamState state = new StreamState();

        emitter.onCompletion(state::closeAll);
        emitter.onTimeout(() -> {
            state.closeAll();
            emitter.complete();
        });
        emitter.onError(ignored -> state.closeAll());

        sendSafe(emitter, "snapshot", ChatResponse.from(chat), state);

        for (ChatEvent event : chatEvents.history(chatId)) {
            sendSafe(emitter, "chat", event, state);
            subscribeCouncilIfTurnStarted(event, emitter, state);
        }
        chat.turns().stream()
                .map(turn -> turn.councilSessionId())
                .forEach(sessionId -> subscribeCouncil(sessionId, emitter, state));

        AutoCloseable chatSubscription = chatEvents.subscribe(chatId, event -> {
            sendSafe(emitter, "chat", event, state);
            subscribeCouncilIfTurnStarted(event, emitter, state);
        });
        state.add(chatSubscription);
        return emitter;
    }

    private void subscribeCouncilIfTurnStarted(ChatEvent event, SseEmitter emitter, StreamState state) {
        if (!"TURN_STARTED".equals(event.type())) {
            return;
        }
        Object sessionId = event.payload().get("councilSessionId");
        if (sessionId instanceof String value) {
            subscribeCouncil(value, emitter, state);
        }
    }

    private void subscribeCouncil(String sessionId, SseEmitter emitter, StreamState state) {
        if (sessionId == null || sessionId.isBlank() || !state.addCouncilSession(sessionId)) {
            return;
        }
        for (CouncilEvent event : councilEvents.history(sessionId)) {
            sendSafe(emitter, "council", event, state);
        }
        AutoCloseable subscription = councilEvents.subscribe(
                sessionId,
                event -> sendSafe(emitter, "council", event, state));
        state.add(subscription);
    }

    private void sendSafe(SseEmitter emitter, String eventName, Object data, StreamState state) {
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event().name(eventName).data(data));
            }
        } catch (IOException | IllegalStateException ex) {
            state.closeAll();
            emitter.completeWithError(ex);
        }
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<String> handleNotFound(NoSuchElementException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ex.getMessage());
    }

    private static class StreamState {
        private final List<AutoCloseable> subscriptions = new CopyOnWriteArrayList<>();
        private final Set<String> councilSessionIds = ConcurrentHashMap.newKeySet();

        private void add(AutoCloseable subscription) {
            subscriptions.add(subscription);
        }

        private boolean addCouncilSession(String sessionId) {
            return councilSessionIds.add(sessionId);
        }

        private void closeAll() {
            subscriptions.forEach(subscription -> {
                try {
                    subscription.close();
                } catch (Exception ignored) {
                    // Best-effort cleanup for local SSE subscribers.
                }
            });
            subscriptions.clear();
            councilSessionIds.clear();
        }
    }
}
