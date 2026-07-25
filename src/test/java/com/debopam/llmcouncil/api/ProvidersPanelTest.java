package com.debopam.llmcouncil.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The providers panel tells a user where credentials go, and takes none.
 *
 * <p>Read as text, in the manner of {@code DockerComposeConfigurationTest},
 * because the property being protected is not behaviour that can be exercised —
 * it is the <em>absence</em> of an input control. A rendering test would confirm
 * the fields that exist and say nothing about the one that must never be added.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProvidersPanelTest {

    private static final Path PANEL = Path.of("src/main/resources/static/js/providers.js");
    private static final Path PAGE = Path.of("src/main/resources/static/index.html");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void servesThePanelModule() throws Exception {
        mockMvc.perform(get("/js/providers.js")).andExpect(status().isOk());
    }

    @Test
    void thePanelIsBackedByTheCatalogProviderSection() throws Exception {
        // The panel renders this and nothing else, which is what keeps it
        // structurally unable to carry a key: availability is inferred from the
        // clients that were built, never read from a credential.
        mockMvc.perform(get("/api/council/catalog").param("include", "providers"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.providers").isArray())
               .andExpect(jsonPath("$.providers[?(@.provider == 'ollama')]").isNotEmpty())
               .andExpect(content().string(containsString("requiredEnvVar")));
    }

    @Test
    void tellsAUserExactlyWhatToSetForAnInactiveProvider() throws Exception {
        String source = Files.readString(PANEL, StandardCharsets.UTF_8);

        assertTrue(source.contains(
                           "Set ${provider.requiredEnvVar} in your environment or .env file and restart."),
                   "the instruction must name the variable the server reported, not a guess");
        assertTrue(source.contains("This application never stores API keys."),
                   "a panel about credentials that does not say where they are not stored "
                   + "invites the user to look for somewhere to put one");
    }

    @Test
    void thePanelOffersNowhereToTypeACredential() throws Exception {
        String source = Files.readString(PANEL, StandardCharsets.UTF_8);
        String page = Files.readString(PAGE, StandardCharsets.UTF_8);

        // Matched on how a control would actually be built. A bare search for
        // "input" would flag the comment explaining why there is none.
        for (String control : new String[]{
                "el(\"input", "el(\"textarea", "el(\"select", "el(\"form",
                "<input", "<textarea", "createElement", "contenteditable"}) {
            assertFalse(source.contains(control),
                        "the providers panel must build no " + control
                        + ": the application never reads a credential from configuration, so a "
                        + "field for one would promise what the rest of the system refuses");
        }
        assertTrue(page.contains("id=\"providers-panel\""),
                   "the panel must be reachable from the page it is documented on");
    }

    @Test
    void thePanelBuildsNodesRatherThanMarkup() throws Exception {
        // Provider reasons come from configuration and from the Ollama daemon.
        // Neither may be parsed as markup, which is the rule the whole UI keeps.
        String source = Files.readString(PANEL, StandardCharsets.UTF_8);
        assertFalse(source.contains("innerHTML"));
    }
}
