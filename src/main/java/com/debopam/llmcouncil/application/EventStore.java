package com.debopam.llmcouncil.application;

import com.debopam.llmcouncil.domain.CouncilEvent;

import java.util.List;

/**
 * Where council events are kept and replayed from.
 *
 * <p>This is the persistence half of what {@link EventPublisher} used to do
 * alone. The other half — handing an event to whoever is streaming right now —
 * is {@link EventBroker}, and the two are genuinely different jobs: a durable
 * implementation of this interface still needs an in-memory broker beside it,
 * because an SSE stream cannot be served from a table.
 */
public interface EventStore {

    /**
     * Record an event.
     *
     * @param event the event to keep
     * @return the stored event, which may carry fields the store assigned
     * @throws RuntimeException if the event could not be stored; callers on the
     *                          council hot path must treat that as an
     *                          observability loss, never as a run failure
     */
    CouncilEvent append(CouncilEvent event);

    /**
     * Replay everything recorded for one session, oldest first.
     *
     * @param sessionId the council session
     * @return the session's events, empty when there are none or the session is
     *         unknown
     */
    List<CouncilEvent> history(String sessionId);

    /**
     * Replay the events of one chat's turns from a position onwards.
     *
     * <p>Scoped by chat rather than by session because that is what a chat's
     * SSE stream is: the council events of every turn in it, interleaved with
     * the chat's own. Events belonging to no chat — the direct
     * {@code POST /sessions} path — are never returned here whatever the
     * cursor, because they have no position in any chat's sequence.
     *
     * @param chatId  the chat whose turns to replay
     * @param chatSeq the last position the client already has; zero for
     *                everything
     * @return the chat's council events after that position, in position order
     */
    List<CouncilEvent> sinceInChat(String chatId, long chatSeq);
}
