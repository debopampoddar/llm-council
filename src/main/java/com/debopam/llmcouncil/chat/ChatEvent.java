package com.debopam.llmcouncil.chat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * An event in a chat's own timeline — a turn starting, finishing, or failing.
 *
 * @param chatSeq    this event's position in its chat's sequence, counting from
 *                   1. That sequence spans this chat's events <em>and</em> the
 *                   council events of every turn in it, which is what lets one
 *                   reconnect cursor cover a stream multiplexing both
 * @param id         a unique id, stable across replays
 * @param chatId     the chat this event belongs to
 * @param occurredAt when the event happened
 * @param type       the event type
 * @param payload    structured detail
 */
public record ChatEvent(
        String id,
        String chatId,
        Instant occurredAt,
        String type,
        Map<String, Object> payload,
        long chatSeq
) {

    /** The sequence of an event that has not been allocated a position yet. */
    public static final long UNASSIGNED_SEQ = 0L;

    /**
     * Create an event that has not been sequenced or stored yet.
     *
     * @param chatId  the chat
     * @param type    the event type
     * @param payload structured detail
     * @return the unsequenced event
     */
    public static ChatEvent of(String chatId, String type, Map<String, Object> payload) {
        return new ChatEvent(UUID.randomUUID().toString(), chatId, Instant.now(), type,
                             Map.copyOf(payload), UNASSIGNED_SEQ);
    }

    /**
     * Return a copy carrying the position the allocator assigned.
     *
     * @param assignedSeq this event's position in its chat, counting from 1
     * @return the sequenced event
     */
    public ChatEvent withChatSeq(long assignedSeq) {
        return new ChatEvent(id, chatId, occurredAt, type, payload, assignedSeq);
    }
}
