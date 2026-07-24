package com.debopam.llmcouncil.persistence.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;

/**
 * The one place a stored row becomes a domain record and back.
 *
 * <p>Every durable store here keeps the record itself in a {@code document}
 * column of JSON and uses scalar columns only for filtering and ordering. That
 * is what lets the domain stay {@code record}s: JPA entities cannot be records —
 * Hibernate wants a no-arg constructor, non-final fields and setters — so an ORM
 * would have forced a parallel entity model and a mapping layer for a query
 * surface that is {@code findById}, {@code findAll} ordered by {@code updatedAt},
 * and {@code delete}.
 *
 * <p>Instants are stored as epoch milliseconds. See the migration script for
 * why: the two supported engines do not agree on what a {@code TIMESTAMP}
 * column is, and ordering that is right on one engine and approximate on the
 * other is the kind of defect that shows up as a session list in a slightly
 * wrong order and is never traced back here.
 */
public class DocumentMapper {

    private final ObjectMapper objectMapper;

    /**
     * @param objectMapper the application's Jackson mapper, already configured
     *                     with JSR-310 support for {@link Instant} fields
     */
    public DocumentMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Serialise a record for its {@code document} column.
     *
     * @param value the record to store
     * @return its JSON form
     * @throws IllegalStateException if the value cannot be serialised, which is
     *                               a programming error rather than a data one
     */
    public String toDocument(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "Unable to serialise " + value.getClass().getSimpleName() + " for storage", ex);
        }
    }

    /**
     * Deserialise a stored {@code document} column.
     *
     * @param document the stored JSON
     * @param type     the record type to read it as
     * @param <T>      the record type
     * @return the reconstructed record
     * @throws IllegalStateException if the stored JSON does not fit the type
     */
    public <T> T fromDocument(String document, Class<T> type) {
        try {
            return objectMapper.readValue(document, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "Unable to read stored " + type.getSimpleName() + " document", ex);
        }
    }

    /**
     * A row mapper that reads the {@code document} column and nothing else.
     *
     * @param type the record type stored in the column
     * @param <T>  the record type
     * @return a row mapper for that type
     */
    public <T> RowMapper<T> documentRowMapper(Class<T> type) {
        return (resultSet, rowNumber) -> fromDocument(resultSet.getString("document"), type);
    }

    /**
     * Convert an instant to its stored form.
     *
     * @param instant the instant, or null
     * @return epoch milliseconds, or 0 for a null instant
     */
    public static long toEpochMillis(Instant instant) {
        return instant == null ? 0L : instant.toEpochMilli();
    }

    /**
     * Convert a stored timestamp back.
     *
     * @param epochMillis the stored value
     * @return the instant it represents
     */
    public static Instant fromEpochMillis(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis);
    }
}
