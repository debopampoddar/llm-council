package com.debopam.llmcouncil.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigWorkbenchContractTest {

    private static final Path STATIC = Path.of("src/main/resources/static");
    private static final int GUARD_WINDOW = 900;

    @Test
    void theWorkbenchExposesTheCompleteSafeWorkflow() throws IOException {
        String api = read("js/config-api.js");
        for (String endpoint : new String[]{"/schema", "/export", "/import", "/validate",
                                            "/preview", "/draft", "/models/probe"}) {
            assertTrue(api.contains(endpoint), "missing workbench endpoint " + endpoint);
        }
    }

    @Test
    void thereIsOneWriteCallAndItIsBehindValidationAndConfirmation() throws IOException {
        String source = read("js/config.js");

        assertEquals(1, occurrences(source, "configApi.save("));
        String guard = precedingText(source, "configApi.save(");
        assertTrue(guard.contains("state.confirming"));
        assertTrue(guard.contains("state.validation?.valid"));
        assertTrue(guard.contains("state.validatedSource !== dom.source.value"));
    }

    @Test
    void cloudProbeIsAcknowledgedInTheUiAndOnTheWire() throws IOException {
        String source = read("js/config.js");
        String api = read("js/config-api.js");
        String html = read("config.html");

        assertTrue(html.contains("Make one billable call"));
        assertTrue(source.contains("dom.probeAck.checked"));
        assertTrue(source.contains("dom.runProbe.disabled = cloud && !dom.probeAck.checked"));
        assertTrue(api.contains("acknowledgeCloudCall"));
    }

    @Test
    void configurationAndFailuresAreNeverRenderedAsMarkup() throws IOException {
        for (String module : new String[]{"js/config.js", "js/config-api.js"}) {
            assertFalse(read(module).contains("innerHTML"), module + " must build DOM nodes only");
        }
    }

    private String read(String relative) throws IOException {
        return Files.readString(STATIC.resolve(relative), StandardCharsets.UTF_8);
    }

    private int occurrences(String source, String needle) {
        int count = 0;
        for (int index = source.indexOf(needle); index >= 0;
             index = source.indexOf(needle, index + needle.length())) count++;
        return count;
    }

    private String precedingText(String source, String needle) {
        int index = source.indexOf(needle);
        return index < 0 ? "" : source.substring(Math.max(0, index - GUARD_WINDOW), index);
    }
}
