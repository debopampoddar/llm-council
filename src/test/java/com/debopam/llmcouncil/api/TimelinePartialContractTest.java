package com.debopam.llmcouncil.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the browser distinction between a no-op stage and partial per-draft work. */
class TimelinePartialContractTest {

    @Test
    void perDraftScoreSkipProducesAPartialRowAndProtocolStatus() throws IOException {
        String source = Files.readString(
                Path.of("src/main/resources/static/js/timeline.js"), StandardCharsets.UTF_8);

        assertTrue(source.contains("type === \"SCORE_SKIPPED\" && payload.draftId"));
        assertTrue(source.contains("current.status = \"partial\""));
        assertTrue(source.contains("type === \"PROTOCOL_PARTIAL\""));
        assertTrue(source.contains("scored ${score.payload.scoreCount}/${score.payload.draftCount} drafts"));
    }

    @Test
    void partialRowsHaveADistinctVisualState() throws IOException {
        String css = Files.readString(
                Path.of("src/main/resources/static/css/app.css"), StandardCharsets.UTF_8);

        assertTrue(css.contains(".tl-node.partial"));
        assertTrue(css.contains(".tl-row.is-partial"));
    }
}
