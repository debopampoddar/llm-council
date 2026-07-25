package com.debopam.llmcouncil.api;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Saving, exporting, and importing the overlay.
 *
 * <p>Runs against a real file in a temporary directory rather than a mocked
 * filesystem, because every property worth asserting here is about what is on
 * disk afterwards: that an invalid save leaves no file, that a second save leaves
 * the first one recoverable, and that no temporary file survives either way.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConfigWriteApiTest {

    private static final Path DIRECTORY = createTemporaryDirectory();
    private static final Path OVERLAY = DIRECTORY.resolve("council-user.yml");
    private static final Path BACKUP = DIRECTORY.resolve("council-user.yml.bak");

    /** A configuration that validates cleanly. */
    private static final String FIRST = """
            {
              "version": 1,
              "models": [{
                "id": "first-critic",
                "provider": "ollama",
                "providerModelId": "qwen2.5:14b",
                "role": "MEMBER",
                "councilRole": "CRITIC",
                "modelFamily": "qwen"
              }]
            }
            """;

    /** A second valid configuration, so a save has something to replace. */
    private static final String SECOND = """
            {
              "version": 1,
              "models": [{
                "id": "second-critic",
                "provider": "ollama",
                "providerModelId": "phi4",
                "role": "MEMBER",
                "councilRole": "CRITIC",
                "modelFamily": "phi"
              }]
            }
            """;

    @DynamicPropertySource
    static void overlayLocation(DynamicPropertyRegistry registry) {
        registry.add("council.userConfigPath", OVERLAY::toString);
    }

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void clearOverlay() throws IOException {
        Files.deleteIfExists(OVERLAY);
        Files.deleteIfExists(BACKUP);
    }

    @AfterAll
    static void removeTemporaryDirectory() throws IOException {
        try (var paths = Files.walk(DIRECTORY)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });
        }
    }

    // ── Saving ──────────────────────────────────────────────────────────

    @Test
    void savesAValidConfigurationAndSaysARestartIsNeeded() throws Exception {
        mockMvc.perform(save(FIRST))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.written").value(true))
               .andExpect(jsonPath("$.restartRequired").value(true))
               .andExpect(jsonPath("$.validation.valid").value(true))
               // Nothing to back up on a first save, so the field is absent
               // rather than pointing at a file that does not exist.
               .andExpect(jsonPath("$.backupPath").doesNotExist());

        assertTrue(Files.exists(OVERLAY));
        assertTrue(Files.readString(OVERLAY).contains("first-critic"));
    }

    @Test
    void theSavedFileIsReadableAsTheDraftAgain() throws Exception {
        mockMvc.perform(save(FIRST)).andExpect(status().isOk());

        mockMvc.perform(get("/api/council/config/draft"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.models[?(@.id == 'first-critic')]").isNotEmpty());
    }

    @Test
    void savingDoesNotApply() throws Exception {
        // The whole reason restartRequired exists. The catalog is pinned at boot
        // and in-flight runs hold their own snapshot of it; if a save quietly
        // swapped either, this model would already be selectable.
        mockMvc.perform(save(FIRST)).andExpect(status().isOk());

        mockMvc.perform(get("/api/council/catalog")
                                .param("include", "models")
                                .param("includeTestOnly", "true"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.models[?(@.id == 'first-critic')]").isEmpty());
    }

    @Test
    void refusesToWriteAnythingWhenThereIsAnError() throws Exception {
        mockMvc.perform(save("""
                       {"models": [{"id": "hot", "provider": "ollama",
                                    "providerModelId": "phi4", "temperature": 9.9,
                                    "role": "MEMBER"}]}
                       """))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.written").value(false))
               .andExpect(jsonPath("$.restartRequired").value(false))
               .andExpect(jsonPath("$.validation.valid").value(false));

        assertFalse(Files.exists(OVERLAY), "an invalid configuration must leave no file behind");
    }

    @Test
    void refusesTheWholeDocumentRatherThanTheEntitiesThatFailed() throws Exception {
        // A partial save would leave the user's file saying something they did
        // not write, and looking like it saved cleanly.
        mockMvc.perform(save("""
                       {"models": [
                         {"id": "good-one", "provider": "ollama", "providerModelId": "phi4",
                          "role": "MEMBER", "modelFamily": "phi"},
                         {"id": "bad-one", "provider": "cohere", "providerModelId": "command-r",
                          "role": "MEMBER", "modelFamily": "cohere"}]}
                       """))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.written").value(false));

        assertFalse(Files.exists(OVERLAY));
    }

    @Test
    void anInvalidSaveLeavesAPreviousConfigurationIntact() throws Exception {
        mockMvc.perform(save(FIRST)).andExpect(jsonPath("$.written").value(true));
        String saved = Files.readString(OVERLAY);

        mockMvc.perform(save("""
                       {"policies": {"broken": {"protocolId": "nope",
                                                "memberModelIds": ["local-llama3"],
                                                "chairModelId": "local-chair"}}}
                       """))
               .andExpect(jsonPath("$.written").value(false));

        assertTrue(Files.readString(OVERLAY).equals(saved),
                   "a refused save must not disturb the configuration already in place");
    }

    @Test
    void keepsExactlyOnePreviousVersion() throws Exception {
        mockMvc.perform(save(FIRST)).andExpect(jsonPath("$.written").value(true));
        mockMvc.perform(save(SECOND))
               .andExpect(jsonPath("$.written").value(true))
               .andExpect(jsonPath("$.backupPath").value(BACKUP.toAbsolutePath().toString()));

        assertTrue(Files.readString(OVERLAY).contains("second-critic"),
                   "the destination holds the new configuration");
        assertTrue(Files.readString(BACKUP).contains("first-critic"),
                   "the backup holds the version that was replaced, not the one replacing it");
        assertFalse(Files.readString(BACKUP).contains("second-critic"));
    }

    @Test
    void leavesNoTemporaryFilesBehind() throws Exception {
        mockMvc.perform(save(FIRST)).andExpect(status().isOk());
        mockMvc.perform(save(SECOND)).andExpect(status().isOk());

        List<String> leftovers;
        try (var paths = Files.list(DIRECTORY)) {
            leftovers = paths.map(path -> path.getFileName().toString())
                             .filter(name -> name.endsWith(".tmp"))
                             .toList();
        }
        assertTrue(leftovers.isEmpty(), "the atomic write left scratch files behind: " + leftovers);
    }

    // ── Credentials ─────────────────────────────────────────────────────

    @Test
    void refusesToSaveADocumentCarryingAnApiKeyWithoutEchoingIt() throws Exception {
        String secret = "sk-ant-never-write-this-anywhere-0123456789";

        mockMvc.perform(save("""
                       {"models": [{"id": "leaky", "provider": "anthropic",
                                    "providerModelId": "claude-sonnet-4",
                                    "apiKey": "%s"}]}
                       """.formatted(secret)))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.valid").value(false))
               .andExpect(content().string(containsString("apiKey")))
               .andExpect(content().string(not(containsString(secret))));

        assertFalse(Files.exists(OVERLAY), "a credential must never reach disk, even briefly");
    }

    @Test
    void aCredentialCannotDisplaceAnAlreadySavedConfiguration() throws Exception {
        mockMvc.perform(save(FIRST)).andExpect(jsonPath("$.written").value(true));
        String saved = Files.readString(OVERLAY);

        mockMvc.perform(save("""
                       {"models": [{"id": "leaky", "provider": "anthropic",
                                    "providerModelId": "claude-sonnet-4",
                                    "apiKey": "hunter2"}]}
                       """))
               .andExpect(status().isBadRequest());

        assertTrue(Files.readString(OVERLAY).equals(saved));
        assertFalse(Files.exists(BACKUP), "a refused save must not even rotate the backup");
    }

    // ── Export and import ───────────────────────────────────────────────

    @Test
    void exportsTheCurrentConfigurationAsYaml() throws Exception {
        mockMvc.perform(save(FIRST)).andExpect(status().isOk());

        mockMvc.perform(get("/api/council/config/export"))
               .andExpect(status().isOk())
               .andExpect(header().string("Content-Disposition",
                                          containsString("council-user.yml")))
               .andExpect(content().string(containsString("first-critic")))
               // The header is what tells whoever receives the file where
               // credentials actually go, since it has none of its own.
               .andExpect(content().string(containsString("never contains credentials")));
    }

    @Test
    void exportsAnEmptyDocumentWhenThereIsNoConfiguration() throws Exception {
        mockMvc.perform(get("/api/council/config/export"))
               .andExpect(status().isOk())
               .andExpect(content().string(containsString("version")));
    }

    @Test
    void anExportCanBeImportedBack() throws Exception {
        mockMvc.perform(save(FIRST)).andExpect(status().isOk());
        String exported = mockMvc.perform(get("/api/council/config/export"))
                                 .andReturn().getResponse().getContentAsString();

        mockMvc.perform(importYaml(exported))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.validation.valid").value(true))
               .andExpect(jsonPath("$.document.models[?(@.id == 'first-critic')]").isNotEmpty());
    }

    @Test
    void importingValidatesButNeverWrites() throws Exception {
        mockMvc.perform(save(FIRST)).andExpect(status().isOk());
        String saved = Files.readString(OVERLAY);

        mockMvc.perform(importYaml("""
                       version: 1
                       models:
                         - id: imported-critic
                           provider: ollama
                           providerModelId: phi4
                           role: MEMBER
                           modelFamily: phi
                       """))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.validation.valid").value(true));

        assertTrue(Files.readString(OVERLAY).equals(saved),
                   "import is validate-only; the user confirms with a save");
    }

    @Test
    void importingReportsProblemsWithoutRefusingTheDocument() throws Exception {
        mockMvc.perform(importYaml("""
                       version: 1
                       models:
                         - id: bad-import
                           provider: cohere
                           providerModelId: command-r
                           role: MEMBER
                       """))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.validation.valid").value(false))
               .andExpect(jsonPath("$.validation.issues[?(@.field == 'provider')]").isNotEmpty());
    }

    @Test
    void refusesAnImportCarryingACredential() throws Exception {
        String secret = "sk-ant-imported-secret-value-0123456789";

        mockMvc.perform(importYaml("""
                       version: 1
                       apiKey: %s
                       """.formatted(secret)))
               .andExpect(status().isBadRequest())
               .andExpect(content().string(not(containsString(secret))));
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private org.springframework.test.web.servlet.RequestBuilder save(String body) {
        return put("/api/council/config/draft")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.getBytes(StandardCharsets.UTF_8));
    }

    private org.springframework.test.web.servlet.RequestBuilder importYaml(String body) {
        return post("/api/council/config/import")
                .contentType(MediaType.parseMediaType("application/yaml"))
                .content(body.getBytes(StandardCharsets.UTF_8));
    }

    private static Path createTemporaryDirectory() {
        try {
            return Files.createTempDirectory("llm-council-config-write");
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
