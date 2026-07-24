package com.debopam.llmcouncil.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Replayable event emitted by the council engine.
 *
 * @param id         a unique id, stable across replays
 * @param sessionId  the council session this event belongs to
 * @param occurredAt when the event happened
 * @param stage      the protocol stage that emitted it, or a pseudo-stage
 * @param type       the event type
 * @param modelId    the model involved, or null for stage-level events
 * @param payload    structured detail
 * @param seq        this event's position in its session, counting from 1
 * @param chatId     the chat whose stream this event also belongs to, or null
 *                   for a session created directly rather than by a chat turn
 * @param chatSeq    this event's position in that chat's sequence, or
 *                   {@link #UNASSIGNED_SEQ} when no chat owns it
 */
public record CouncilEvent(
        String id,
        String sessionId,
        Instant occurredAt,
        String stage,
        String type,
        String modelId,
        Map<String, Object> payload,
        long seq,
        String chatId,
        long chatSeq
) {

    /** The sequence of an event that has not been stored yet. */
    public static final long UNASSIGNED_SEQ = 0L;

    /**
     * Create an event that has not been stored yet.
     *
     * <p>Its {@code seq} is {@link #UNASSIGNED_SEQ} until an event store
     * assigns one. The store is what owns the sequence, because only it knows
     * what has already been recorded for the session.
     *
     * @param sessionId the council session
     * @param stage     the protocol stage
     * @param type      the event type
     * @param modelId   the model involved, or null
     * @param payload   structured detail
     * @return the unsequenced event
     */
    public static CouncilEvent of(String sessionId, String stage, String type,
                                  String modelId, Map<String, Object> payload) {
        return new CouncilEvent(UUID.randomUUID().toString(), sessionId, Instant.now(),
                                stage, type, modelId, Map.copyOf(payload), UNASSIGNED_SEQ,
                                null, UNASSIGNED_SEQ);
    }

    /**
     * Return a copy carrying the sequence the store assigned.
     *
     * @param assignedSeq the event's position in its session, counting from 1
     * @return the sequenced event
     */
    public CouncilEvent withSeq(long assignedSeq) {
        return new CouncilEvent(id, sessionId, occurredAt, stage, type, modelId, payload,
                                assignedSeq, chatId, chatSeq);
    }

    /**
     * Return a copy attributed to the chat whose turn produced it.
     *
     * <p>The chat sequence is separate from {@link #seq()} and spans more: it
     * numbers this council event alongside the chat's own events and the events
     * of every other turn in that chat, which is what lets one cursor cover a
     * stream multiplexing all of them.
     *
     * @param owningChatId    the chat that owns this event's session
     * @param positionInChat  its position in that chat's sequence
     * @return the attributed event
     */
    public CouncilEvent withChatPosition(String owningChatId, long positionInChat) {
        return new CouncilEvent(id, sessionId, occurredAt, stage, type, modelId, payload,
                                seq, owningChatId, positionInChat);
    }
}
