package com.debopam.llmcouncil.persistence.jdbc;

import com.debopam.llmcouncil.domain.CouncilSession;
import com.debopam.llmcouncil.domain.CouncilStatus;
import com.debopam.llmcouncil.domain.ContextPurpose;
import com.debopam.llmcouncil.domain.DepthMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The Jackson round trip the durable stores are built on.
 *
 * <p>This is what lets the domain stay {@code record}s. If a record stopped
 * round-tripping — a new field without a matching constructor parameter, a type
 * Jackson cannot rebuild — every durable store would break at once, and the
 * symptom would be a session that saved cleanly and could not be read back.
 */
class DocumentMapperTest {

    private final DocumentMapper mapper =
            new DocumentMapper(new ObjectMapper().registerModule(new JavaTimeModule()));

    @Test
    void aCouncilSessionSurvivesTheRoundTripFieldForField() {
        CouncilSession original = new CouncilSession(
                "session-1", "Why?", "some context", DepthMode.RIGOROUS, "multi-cloud",
                "policy-1", "protocol-1", CouncilStatus.PARTIAL,
                Instant.parse("2026-01-01T10:00:00Z"), Instant.parse("2026-01-01T10:05:00Z"),
                "the answer", "a warning");

        CouncilSession restored =
                mapper.fromDocument(mapper.toDocument(original), CouncilSession.class);

        assertEquals(original, restored);
    }

    @Test
    void nullFieldsStayNullRatherThanBecomingEmptyStrings() {
        // A fresh session has no policy, protocol, answer or failure reason. If
        // those came back as "" the UI would show an empty failure reason as a
        // failure that happened.
        CouncilSession original =
                CouncilSession.create("session-2", "Why?", null, DepthMode.QUICK, "mock");

        CouncilSession restored =
                mapper.fromDocument(mapper.toDocument(original), CouncilSession.class);

        assertNull(restored.context());
        assertNull(restored.finalAnswer());
        assertNull(restored.failureReason());
        assertEquals(ContextPurpose.EVIDENCE, restored.contextPurpose());
        assertEquals(original, restored);
    }

    @Test
    void instantsKeepMillisecondPrecision() {
        // Truncation to whole seconds would reorder events written inside the
        // same second, which under virtual-thread fan-out is most of them.
        Instant precise = Instant.parse("2026-01-01T10:00:00.123Z");
        CouncilSession original = new CouncilSession(
                "session-3", "Why?", null, DepthMode.QUICK, "mock", null, null,
                CouncilStatus.CREATED, precise, precise, null, null);

        CouncilSession restored =
                mapper.fromDocument(mapper.toDocument(original), CouncilSession.class);

        assertEquals(precise, restored.createdAt());
        assertEquals(123, restored.createdAt().getNano() / 1_000_000);
    }

    @Test
    void epochMillisConversionRoundTrips() {
        Instant instant = Instant.parse("2026-01-01T10:00:00.456Z");

        assertEquals(instant, DocumentMapper.fromEpochMillis(DocumentMapper.toEpochMillis(instant)));
    }

    @Test
    void unreadableStoredJsonFailsLoudlyRatherThanReturningNull() {
        // Positive control for the round-trip assertions above: the mapper is
        // capable of rejecting a document, so their success is Jackson working
        // rather than every path silently returning something.
        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> mapper.fromDocument("{\"depthMode\":\"NOT_A_DEPTH\"}", CouncilSession.class));

        assertEquals("Unable to read stored CouncilSession document", thrown.getMessage());
    }
}
