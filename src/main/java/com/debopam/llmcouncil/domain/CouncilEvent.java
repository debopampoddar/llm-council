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
 */
public record CouncilEvent(
        String id,
        String sessionId,
        Instant occurredAt,
        String stage,
        String type,
        String modelId,
        Map<String, Object> payload,
        long seq
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
                                stage, type, modelId, Map.copyOf(payload), UNASSIGNED_SEQ);
    }

    /**
     * Return a copy carrying the sequence the store assigned.
     *
     * @param assignedSeq the event's position in its session, counting from 1
     * @return the sequenced event
     */
    public CouncilEvent withSeq(long assignedSeq) {
        return new CouncilEvent(id, sessionId, occurredAt, stage, type, modelId, payload,
                                assignedSeq);
    }
}
