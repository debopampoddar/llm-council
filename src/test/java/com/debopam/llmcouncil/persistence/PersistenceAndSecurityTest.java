package com.debopam.llmcouncil.persistence;

import com.debopam.llmcouncil.support.TestCatalogs;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PersistenceAndSecurityTest {
    @TempDir Path temp;
    LocalArtifactStore store;

    @BeforeEach
    void setUp() {
        store = new LocalArtifactStore(
                TestCatalogs.holder(1, temp.resolve("artifacts").toString()), new ObjectMapper());
    }

    @Test
    void textAndJsonArtifactsRoundTripAndListDeterministically() {
        store.writeText("session", "z/output.txt", "hello");
        store.writeJson("session", "a/data.json", Map.of("answer", 42));

        assertEquals(Optional.of("hello"), store.readArtifact("session", "z/output.txt"));
        assertEquals(java.util.List.of("a/data.json", "z/output.txt"), store.listArtifacts("session"));
        assertTrue(store.readArtifact("session", "missing.txt").isEmpty());
    }

    @Test
    void lexicalTraversalIsRejectedForSessionIdsReadsAndWrites() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> store.readArtifact("session", "../../../etc/passwd")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> store.writeText("session", "../other/secret", "x")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> store.listArtifacts("../outside")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> store.deleteSession("")));
    }

    @Test
    void symbolicLinkCannotEscapeArtifactRoot() throws Exception {
        Path outside = temp.resolve("outside.txt");
        Files.writeString(outside, "sensitive");
        Path session = temp.resolve("artifacts/session");
        Files.createDirectories(session);
        Files.createSymbolicLink(session.resolve("escape.txt"), outside);

        assertThrows(IllegalArgumentException.class,
                () -> store.readArtifact("session", "escape.txt"));
        assertFalse(store.listArtifacts("session").contains("escape.txt"));
    }

    @Test
    void deletingOneSessionDoesNotTouchItsNeighbour() {
        store.writeText("remove", "raw/a.txt", "a");
        store.writeText("keep", "raw/b.txt", "b");

        assertTrue(store.deleteSession("remove"));
        assertTrue(store.listArtifacts("remove").isEmpty());
        assertEquals(Optional.of("b"), store.readArtifact("keep", "raw/b.txt"));
        assertFalse(store.deleteSession("remove"));
    }

    @Test
    void atomicWriterKeepsExactlyThePreviousVersion() throws Exception {
        AtomicFileWriter writer = new AtomicFileWriter();
        Path destination = temp.resolve("config.yml");
        writer.write(destination, "v1", true);
        Path backup = writer.write(destination, "v2", true);

        assertEquals("v2", Files.readString(destination));
        assertNotNull(backup);
        assertEquals("v1", Files.readString(backup));
        try (var files = Files.list(temp)) {
            assertEquals(2, files.count());
        }
    }
}
