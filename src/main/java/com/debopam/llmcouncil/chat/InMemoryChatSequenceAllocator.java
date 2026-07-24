package com.debopam.llmcouncil.chat;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One counter per chat, held in memory.
 *
 * <p>Correct for {@code council.persistence.type=memory} because nothing
 * survives a restart there: a chat that outlived the counter would have outlived
 * its events too, so there is nothing for a reissued number to collide with.
 * The durable allocator cannot make that assumption and keeps the counter in the
 * database.
 */
@Component
@ConditionalOnProperty(name = "council.persistence.type", havingValue = "memory",
                       matchIfMissing = true)
public class InMemoryChatSequenceAllocator implements ChatSequenceAllocator {

    private final Map<String, AtomicLong> sequences = new ConcurrentHashMap<>();

    @Override
    public long next(String chatId) {
        return sequences.computeIfAbsent(chatId, ignored -> new AtomicLong()).incrementAndGet();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Without this the map would be the one structure in the process that
     * still grew forever.
     */
    @Override
    public void forget(String chatId) {
        sequences.remove(chatId);
    }
}
