package com.debopam.llmcouncil.persistence.jdbc;

import com.debopam.llmcouncil.chat.ChatSequenceAllocator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The chat sequence counter, kept on the chat's own row.
 *
 * <p>Durable because a chat outlives the process, unlike a council run. An
 * in-memory counter starting at 1 after a restart would hand new events
 * positions already on disk, and a cursor sitting at one of those positions
 * would replay events the client had seen while skipping ones it had not.
 *
 * <p>The bump and the read are one {@code synchronized} block rather than a
 * database transaction. Two statements are needed because H2 has no
 * {@code RETURNING}, and this application is single-process by construction —
 * {@code server.address} defaults to loopback and there is no clustering — so a
 * monitor is the whole of the mutual exclusion required. Both statements are
 * local and take microseconds, so serialising them costs nothing next to the
 * model call that produced the event.
 */
@Component
@ConditionalOnProperty(name = "council.persistence.type", havingValue = "jdbc")
public class JdbcChatSequenceAllocator implements ChatSequenceAllocator {

    private final JdbcTemplate jdbc;

    /**
     * @param jdbc the template over the configured datasource
     */
    public JdbcChatSequenceAllocator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalStateException if the chat has no row, which would mean an
     *                               event was published for a chat that was
     *                               never saved
     */
    @Override
    public synchronized long next(String chatId) {
        int updated = jdbc.update("UPDATE chat_session SET next_seq = next_seq + 1 WHERE id = ?",
                                  chatId);
        if (updated == 0) {
            throw new IllegalStateException(
                    "No chat " + chatId + " to allocate a sequence position from");
        }
        List<Long> allocated = jdbc.queryForList(
                "SELECT next_seq FROM chat_session WHERE id = ?", Long.class, chatId);
        return allocated.getFirst();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Nothing to do: the counter is a column on the chat, and it is deleted
     * with the chat.
     */
    @Override
    public void forget(String chatId) {
    }
}
