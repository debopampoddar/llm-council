package com.debopam.llmcouncil.api;

import com.debopam.llmcouncil.api.dto.ChatMessageRequest;
import com.debopam.llmcouncil.api.dto.ChatResponse;
import com.debopam.llmcouncil.api.dto.ChatSummaryResponse;
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
import org.springframework.web.bind.annotation.DeleteMapping;
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

    /**
     * List every chat, most recently updated first.
     *
     * @return 200 OK with chat summaries, without turn bodies
     */
    @GetMapping
    public ResponseEntity<List<ChatSummaryResponse>> list() {
        return ResponseEntity.ok(chatService.listChats().stream()
                                            .map(ChatSummaryResponse::from)
                                            .toList());
    }

    /**
     * Delete a chat and its turns.
     *
     * @param chatId the chat to delete
     * @return 204 No Content, or 409 Conflict when a turn is still running
     */
    @DeleteMapping("/{chatId}")
    public ResponseEntity<Void> delete(@PathVariable("chatId") String chatId) {
        chatService.deleteChat(chatId);
        return ResponseEntity.noContent().build();
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
            sendSafe(emitter, "chat", event.id(), event, state);
            subscribeCouncilIfTurnStarted(event, emitter, state);
        }
        chat.turns().stream()
                .map(turn -> turn.councilSessionId())
                .forEach(sessionId -> subscribeCouncil(sessionId, emitter, state));

        AutoCloseable chatSubscription = chatEvents.subscribe(chatId, event -> {
            sendSafe(emitter, "chat", event.id(), event, state);
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
            sendSafe(emitter, "council", event.id(), event, state);
        }
        AutoCloseable subscription = councilEvents.subscribe(
                sessionId,
                event -> sendSafe(emitter, "council", event.id(), event, state));
        state.add(subscription);
    }

    private void sendSafe(SseEmitter emitter, String eventName, Object data, StreamState state) {
        sendSafe(emitter, eventName, null, data, state);
    }

    /**
     * Write one frame, tagging it with the event's own id where there is one.
     *
     * <p>The id is what makes a frame identifiable across a reconnect. This
     * stream replays its full history on connect and honours no cursor, so the
     * client dedupes on these ids.
     *
     * <p><b>A server-side cursor is not simply a matter of reading
     * {@code Last-Event-ID}.</b> One stream multiplexes three sources — the chat
     * snapshot, the chat event log, and one council event log per turn — that are
     * interleaved but independently ordered. The browser echoes a single id, and
     * that id identifies a position in whichever source happened to send last;
     * it says nothing about how far the others got. Resuming from it would skip
     * events on every source except one. A real cursor needs either a composite
     * position across all three, or a single ordering shared by them, which is
     * what a durable event store's monotonic sequence would provide.
     *
     * @param emitter   the open stream
     * @param eventName the SSE event name the client listens on
     * @param eventId   the event's id, or null for frames that are not events
     * @param data      the payload, serialised as JSON
     * @param state     subscriptions to close if the stream has gone away
     */
    private void sendSafe(SseEmitter emitter, String eventName, String eventId,
                          Object data, StreamState state) {
        try {
            synchronized (emitter) {
                SseEmitter.SseEventBuilder frame = SseEmitter.event().name(eventName).data(data);
                if (eventId != null && !eventId.isBlank()) {
                    frame = frame.id(eventId);
                }
                emitter.send(frame);
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

    /** A chat that is mid-run cannot be deleted; report that as a conflict. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<String> handleConflict(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ex.getMessage());
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
