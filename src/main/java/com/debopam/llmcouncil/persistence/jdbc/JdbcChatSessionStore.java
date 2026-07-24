package com.debopam.llmcouncil.persistence.jdbc;

import com.debopam.llmcouncil.chat.ChatSession;
import com.debopam.llmcouncil.chat.ChatSessionSnapshot;
import com.debopam.llmcouncil.chat.ChatSessionStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;

/**
 * Chats in a database table, one JSON document per row.
 *
 * <p>This is the store that makes durability visible: a chat is what the user
 * sees in the sidebar, and a sidebar that empties on every restart reads as
 * broken rather than as demo-grade.
 *
 * <p>Rows hold a {@link ChatSessionSnapshot} rather than a {@code ChatSession},
 * because that class cannot round-trip through Jackson and must not be taught
 * to. The store converts at both ends.
 */
@Component
@ConditionalOnProperty(name = "council.persistence.type", havingValue = "jdbc")
public class JdbcChatSessionStore implements ChatSessionStore {

    /** Column order for the upsert, matching the bind order in {@link #save}. */
    private static final List<String> COLUMNS =
            List.of("id", "profile_id", "created_at", "updated_at", "document");

    private final JdbcTemplate jdbc;
    private final DocumentMapper documents;
    private final String upsertSql;

    /**
     * @param jdbc       the template over the configured datasource
     * @param dataSource the same datasource, inspected once for its dialect
     * @param documents  the shared Jackson round trip
     */
    public JdbcChatSessionStore(JdbcTemplate jdbc, DataSource dataSource, DocumentMapper documents) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.upsertSql = SqlDialect.detect(dataSource).upsert("chat_session", "id", COLUMNS);
    }

    @Override
    public void save(ChatSession session) {
        ChatSessionSnapshot snapshot = session.toSnapshot();
        jdbc.update(upsertSql,
                    snapshot.id(),
                    snapshot.profileId(),
                    DocumentMapper.toEpochMillis(snapshot.createdAt()),
                    DocumentMapper.toEpochMillis(snapshot.updatedAt()),
                    documents.toDocument(snapshot));
    }

    @Override
    public Optional<ChatSession> findById(String chatId) {
        List<ChatSession> found = jdbc.query(
                "SELECT document FROM chat_session WHERE id = ?", chatRowMapper(), chatId);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.getFirst());
    }

    /**
     * {@inheritDoc}
     *
     * <p>The {@code id} tiebreaker is not cosmetic. {@code updated_at} is stored
     * in milliseconds, so two chats touched inside the same millisecond compare
     * equal, and without a second key the sidebar would reorder them between one
     * read and the next for no reason the user could see.
     */
    @Override
    public List<ChatSession> findAll() {
        return jdbc.query("SELECT document FROM chat_session ORDER BY updated_at DESC, id DESC",
                          chatRowMapper());
    }

    @Override
    public boolean delete(String chatId) {
        return jdbc.update("DELETE FROM chat_session WHERE id = ?", chatId) > 0;
    }

    /**
     * @return a row mapper that reads the stored snapshot and rebuilds the chat
     */
    private org.springframework.jdbc.core.RowMapper<ChatSession> chatRowMapper() {
        return (resultSet, rowNumber) -> ChatSession.fromSnapshot(
                documents.fromDocument(resultSet.getString("document"), ChatSessionSnapshot.class));
    }
}
