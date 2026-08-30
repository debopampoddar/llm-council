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
import com.debopam.llmcouncil.chat.ChatStreamFrame;
import com.debopam.llmcouncil.chat.ChatStreamReplay;
import com.debopam.llmcouncil.domain.CouncilEvent;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
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
    private final ChatStreamReplay streamReplay;

    public ChatController(ChatCouncilService chatService,
                          ChatEventBroker chatEvents,
                          EventPublisher councilEvents,
                          ChatStreamReplay streamReplay) {
        this.chatService = chatService;
        this.chatEvents = chatEvents;
        this.councilEvents = councilEvents;
        this.streamReplay = streamReplay;
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

    /**
     * Stream one chat: a snapshot, then its events and its turns' council
     * events, live.
     *
     * <p><b>Resuming.</b> A client that already has part of the stream sends the
     * position it reached and gets only what followed. The position is a single
     * integer because every source in this stream — the chat's own events and
     * the council events of each turn — draws its number from one per-chat
     * sequence; without that, a single {@code Last-Event-ID} would locate a
     * position in whichever source happened to send last and say nothing about
     * the others, so resuming from it would skip events on all the rest.
     *
     * <p>Both the header and a query parameter are read. The header is what a
     * browser's own {@code EventSource} reconnect sends; the query parameter is
     * for a client that closes and reopens the stream deliberately — which
     * {@code sse.js} does, to control its own backoff — because a freshly
     * constructed {@code EventSource} sends no header.
     *
     * <p>A first connection, with no cursor, still replays full history through
     * the per-source path rather than through the merged one. That path is
     * lossless: an event whose chat position could not be allocated has no place
     * in the merged ordering and would silently vanish from it.
     *
     * @param chatId          the chat to follow
     * @param lastEventId     the position reached, from the standard header
     * @param lastEventIdParam the same, for clients that reconnect by hand
     * @return the open stream
     */
    @GetMapping(path = "/{chatId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
            @PathVariable("chatId") String chatId,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
            @RequestParam(name = "lastEventId", required = false) String lastEventIdParam) {
        ChatSession chat = chatService.getChat(chatId);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        StreamState state = new StreamState();

        emitter.onCompletion(state::closeAll);
        emitter.onTimeout(() -> {
            state.closeAll();
            emitter.complete();
        });
        emitter.onError(ignored -> state.closeAll());

        // Always sent, resume or not: it is a full state replacement, so a
        // reconnecting client gets the chat's current turns without having to
        // rebuild them from the events it missed.
        sendSafe(emitter, "snapshot", ChatResponse.from(chat), state);

        long cursor = cursorFrom(lastEventId, lastEventIdParam);
        // Resuming replays each source once, merged, so the per-session council
        // history must not be replayed again on top of it — that is the whole
        // saving a cursor exists to make.
        boolean resuming = cursor > 0;
        if (resuming) {
            replayFrom(chatId, cursor, emitter, state);
        } else {
            replayEverything(chatId, emitter, state);
        }

        chat.turns().stream()
                .map(turn -> turn.councilSessionId())
                .forEach(sessionId -> subscribeCouncil(sessionId, emitter, state, !resuming));

        AutoCloseable chatSubscription = chatEvents.subscribe(chatId, event -> {
            sendSafe(emitter, ChatStreamFrame.CHAT, frameId(event.chatSeq()), event, state);
            subscribeCouncilIfTurnStarted(event, emitter, state, false);
        });
        state.add(chatSubscription);
        return emitter;
    }

    /**
     * Replay only what followed the client's position, in one merged run.
     *
     * @param chatId  the chat
     * @param cursor  the position the client reached
     * @param emitter the open stream
     * @param state   subscriptions to close if the stream has gone away
     */
    private void replayFrom(String chatId, long cursor, SseEmitter emitter, StreamState state) {
        for (ChatStreamFrame frame : streamReplay.since(chatId, cursor)) {
            sendSafe(emitter, frame.name(), frameId(frame.chatSeq()), frame.data(), state);
            if (frame.data() instanceof ChatEvent event) {
                subscribeCouncilIfTurnStarted(event, emitter, state, false);
            }
        }
    }

    /**
     * Replay the whole stream, source by source, for a first connection.
     *
     * @param chatId  the chat
     * @param emitter the open stream
     * @param state   subscriptions to close if the stream has gone away
     */
    private void replayEverything(String chatId, SseEmitter emitter, StreamState state) {
        for (ChatEvent event : chatEvents.history(chatId)) {
            sendSafe(emitter, ChatStreamFrame.CHAT, frameId(event.chatSeq()), event, state);
            // True, not false: these turns have already run. Claiming the
            // session here without replaying would make the per-turn loop
            // afterwards skip it as already-followed, and a first connection
            // would receive no council events at all.
            subscribeCouncilIfTurnStarted(event, emitter, state, true);
        }
    }

    /**
     * Read the resume position a client presented.
     *
     * <p>Anything unparseable is treated as no cursor at all, which replays
     * everything. A client that sent nonsense gets duplicates it will dedupe;
     * guessing at a number instead could silently skip the events between.
     *
     * @param header the standard {@code Last-Event-ID} header value, or null
     * @param param  the query parameter value, or null
     * @return the position, or 0 for "start from the beginning"
     */
    private long cursorFrom(String header, String param) {
        String value = header != null && !header.isBlank() ? header : param;
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Math.max(0L, Long.parseLong(value.trim()));
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    /**
     * The frame id a client echoes back as its cursor.
     *
     * @param chatSeq the event's position in the chat's sequence
     * @return the id, or null when the event has no position — an unnumbered
     *         frame must not overwrite the client's cursor with a zero, which
     *         would make its next reconnect replay the whole stream
     */
    private String frameId(long chatSeq) {
        return chatSeq > 0 ? Long.toString(chatSeq) : null;
    }

    /**
     * Follow the council session a {@code TURN_STARTED} event announces.
     *
     * @param event         the chat event, which may or may not be a turn start
     * @param emitter       the open stream
     * @param state         subscriptions to close if the stream has gone away
     * @param replayHistory whether that session's existing events should be sent
     *                      first. True while replaying chat history, where the
     *                      turn announced has already run; false for a turn
     *                      starting now, which has nothing behind it, and false
     *                      on a resume, where the merged replay has already sent
     *                      everything after the cursor
     */
    private void subscribeCouncilIfTurnStarted(ChatEvent event, SseEmitter emitter,
                                               StreamState state, boolean replayHistory) {
        if (!"TURN_STARTED".equals(event.type())) {
            return;
        }
        Object sessionId = event.payload().get("councilSessionId");
        if (sessionId instanceof String value) {
            subscribeCouncil(value, emitter, state, replayHistory);
        }
    }

    /**
     * Follow one turn's council session, optionally replaying what it already
     * emitted.
     *
     * @param sessionId      the council session behind a turn
     * @param emitter        the open stream
     * @param state          subscriptions to close if the stream has gone away
     * @param replayHistory  whether to send the session's existing events first;
     *                       false for a resuming client, which has just been
     *                       sent everything after its cursor and would otherwise
     *                       receive the whole run again
     */
    private void subscribeCouncil(String sessionId, SseEmitter emitter, StreamState state,
                                  boolean replayHistory) {
        if (sessionId == null || sessionId.isBlank() || !state.addCouncilSession(sessionId)) {
            return;
        }
        if (replayHistory) {
            for (CouncilEvent event : councilEvents.history(sessionId)) {
                sendSafe(emitter, ChatStreamFrame.COUNCIL, frameId(event.chatSeq()), event, state);
            }
        }
        AutoCloseable subscription = councilEvents.subscribe(
                sessionId,
                event -> sendSafe(emitter, ChatStreamFrame.COUNCIL,
                                  frameId(event.chatSeq()), event, state));
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
