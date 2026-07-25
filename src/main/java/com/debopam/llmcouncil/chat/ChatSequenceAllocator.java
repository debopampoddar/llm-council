package com.debopam.llmcouncil.chat;

/**
 * Hands out positions in a chat's sequence.
 *
 * <p>One chat's SSE stream multiplexes three independently-ordered sources: the
 * chat snapshot, the chat's own event log, and one council event log per turn.
 * {@code Last-Event-ID} carries a single value, which locates a position in
 * whichever source happened to send last and says nothing about the others —
 * so resuming from it would skip events on every source but one.
 *
 * <p>The fix is to give those sources one shared ordering, and this is where it
 * comes from. Every chat event and every council event belonging to one of that
 * chat's turns takes its number from the same allocator, so the cursor is a
 * single integer and the client's dedupe goes back to being a backstop rather
 * than the mechanism.
 *
 * <p>The position is allocated <em>at append time</em>, not per stream
 * connection. A per-connection counter would be simpler and would not work: a
 * cursor is only usable if frame N means the same event on the reconnect as it
 * did on the original connection, and a counter that restarts with each
 * connection re-numbers a re-interleaved replay differently every time.
 */
public interface ChatSequenceAllocator {

    /**
     * Allocate the next position in one chat's sequence.
     *
     * @param chatId the chat
     * @return the next position, counting from 1
     */
    long next(String chatId);

    /**
     * Forget a deleted chat's counter.
     *
     * <p>A no-op for allocators that keep the counter alongside the chat itself
     * and lose it with the chat.
     *
     * @param chatId the chat that has been deleted
     */
    void forget(String chatId);
}
