package com.debopam.llmcouncil.persistence.jdbc;

import com.debopam.llmcouncil.chat.ChatEvent;
import com.debopam.llmcouncil.chat.ChatEventStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Chat events in a database table, one JSON document per row.
 *
 * <p>Ordered by {@code chat_seq} rather than by {@code occurred_at}, because
 * that column is the shared ordering the reconnect cursor seeks on and because
 * several of a turn's events land inside the same millisecond.
 *
 * <p>Positions arrive already allocated: the broker asks
 * {@code ChatSequenceAllocator} for one before calling here, since the same
 * sequence also numbers the council events of this chat's turns and cannot be
 * owned by either table alone.
 */
@Component
@ConditionalOnProperty(name = "council.persistence.type", havingValue = "jdbc")
public class JdbcChatEventStore implements ChatEventStore {

    private final JdbcTemplate jdbc;
    private final DocumentMapper documents;

    /**
     * @param jdbc      the template over the configured datasource
     * @param documents the shared Jackson round trip
     */
    public JdbcChatEventStore(JdbcTemplate jdbc, DocumentMapper documents) {
        this.jdbc = jdbc;
        this.documents = documents;
    }

    @Override
    public ChatEvent append(ChatEvent event) {
        jdbc.update("INSERT INTO chat_event "
                    + "(id, chat_id, occurred_at, chat_seq, type, document) "
                    + "VALUES (?, ?, ?, ?, ?, ?)",
                    event.id(),
                    event.chatId(),
                    DocumentMapper.toEpochMillis(event.occurredAt()),
                    event.chatSeq(),
                    event.type(),
                    documents.toDocument(event));
        return event;
    }

    @Override
    public List<ChatEvent> history(String chatId) {
        return jdbc.query("SELECT document FROM chat_event WHERE chat_id = ? ORDER BY chat_seq",
                          documents.documentRowMapper(ChatEvent.class),
                          chatId);
    }

    @Override
    public List<ChatEvent> since(String chatId, long chatSeq) {
        return jdbc.query("SELECT document FROM chat_event "
                          + "WHERE chat_id = ? AND chat_seq > ? ORDER BY chat_seq",
                          documents.documentRowMapper(ChatEvent.class),
                          chatId, chatSeq);
    }

    @Override
    public void deleteChat(String chatId) {
        jdbc.update("DELETE FROM chat_event WHERE chat_id = ?", chatId);
    }
}
