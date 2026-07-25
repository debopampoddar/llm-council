package com.debopam.llmcouncil.application;

import java.util.Optional;

/**
 * Says whether a council session belongs to a chat, and where its next event
 * falls in that chat's sequence.
 *
 * <p>A council session can exist with no chat at all — the direct
 * {@code POST /sessions} then {@code /run} path, streamed by
 * {@code GET /sessions/{id}/events} — so this returns an optional rather than
 * assuming every run has a conversation around it. Events outside a chat carry
 * their per-session sequence and nothing else.
 *
 * <p>The interface lives here and is implemented in the chat package so the
 * dependency runs the way it already does: chat knows about council, and
 * council does not know about chat. A publisher that imported the chat
 * aggregate to number its events would invert that for one field.
 */
public interface ChatAttribution {

    /** Nothing belongs to a chat. The default for the direct-session path and for tests. */
    ChatAttribution NONE = sessionId -> Optional.empty();

    /**
     * Allocate this council session's next position in its chat's sequence.
     *
     * <p>Called once per event, and allocating is a side effect: two calls
     * return two different positions.
     *
     * @param councilSessionId the council session about to emit an event
     * @return the chat and position, or empty when no chat owns this session
     */
    Optional<ChatPosition> nextPositionFor(String councilSessionId);
}
