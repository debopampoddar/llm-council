package com.debopam.llmcouncil.persistence;

import com.debopam.llmcouncil.config.CouncilRuntimeSettings;
import com.debopam.llmcouncil.config.TestCatalogs;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalArtifactStoreTest {

    @TempDir
    Path tempDir;

    private LocalArtifactStore store;

    @BeforeEach
    void setUp() {
        store = new LocalArtifactStore(
                TestCatalogs.holder(new CouncilRuntimeSettings(1, 4, tempDir.toString())),
                new ObjectMapper());
    }

    @Test
    void readsBackWhatItWrote() {
        store.writeText("session-1", "drafts/draft-a.txt", "hello council");

        assertEquals(Optional.of("hello council"), store.readArtifact("session-1", "drafts/draft-a.txt"));
    }

    @Test
    void returnsEmptyForMissingArtifact() {
        store.writeText("session-1", "drafts/draft-a.txt", "hello council");

        assertTrue(store.readArtifact("session-1", "drafts/nope.txt").isEmpty());
        assertTrue(store.readArtifact("no-such-session", "drafts/draft-a.txt").isEmpty());
    }

    @Test
    void returnsEmptyRatherThanReadingADirectory() {
        store.writeText("session-1", "drafts/draft-a.txt", "hello council");

        assertTrue(store.readArtifact("session-1", "drafts").isEmpty());
    }

    @Test
    void rejectsRelativeTraversalOutOfTheSessionDirectory() {
        store.writeText("session-1", "drafts/draft-a.txt", "hello council");

        assertThrows(IllegalArgumentException.class,
                     () -> store.readArtifact("session-1", "../../../etc/passwd"));
        assertThrows(IllegalArgumentException.class,
                     () -> store.readArtifact("session-1", "../session-2/secret.txt"));
    }

    @Test
    void rejectsAbsolutePathsThatWouldEscapeTheSessionDirectory() {
        assertThrows(IllegalArgumentException.class,
                     () -> store.readArtifact("session-1", "/etc/passwd"));
    }

    @Test
    void traversalGuardAppliesToWritesToo() {
        assertThrows(IllegalArgumentException.class,
                     () -> store.writeText("session-1", "../escaped.txt", "should not be written"));
    }
}
