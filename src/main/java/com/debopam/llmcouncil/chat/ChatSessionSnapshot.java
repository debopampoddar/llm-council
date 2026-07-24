package com.debopam.llmcouncil.chat;

import com.debopam.llmcouncil.domain.DepthMode;

import java.time.Instant;
import java.util.List;

/**
 * The serialization form of a {@link ChatSession}.
 *
 * <p>Every other durable type here is already a record and round-trips through
 * Jackson as it stands. {@code ChatSession} is the exception: it is a mutable
 * class with a private turn list, no no-arg constructor and no setters, so
 * Jackson cannot rebuild one.
 *
 * <p>The fix is a snapshot rather than annotations on {@code ChatSession}
 * itself, because that class's synchronization is load-bearing —
 * {@code ChatCouncilService.handleCompletion} mutates turns from virtual threads
 * while SSE readers call {@code turns()}. Teaching Jackson to write into it
 * directly would reach past every one of those locks.
 *
 * <p>Public rather than package-private because the JDBC store that serialises
 * it lives in another package.
 *
 * @param id         the chat id
 * @param profileId  the council profile every turn runs under
 * @param depthMode  the depth mode every turn runs at
 * @param summary    the rolling conversation summary fed into each turn
 * @param turns      the turns, oldest first
 * @param createdAt  when the chat was created — not when it was last stored
 * @param updatedAt  when the chat last changed
 */
public record ChatSessionSnapshot(
        String id,
        String profileId,
        DepthMode depthMode,
        String summary,
        List<ChatTurn> turns,
        Instant createdAt,
        Instant updatedAt
) {

    /** Defensive copy of the turn list, so a stored snapshot cannot be mutated behind the store. */
    public ChatSessionSnapshot {
        turns = turns == null ? List.of() : List.copyOf(turns);
    }
}
