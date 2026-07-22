package com.debopam.llmcouncil.config.user;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers reading the overlay from disk.
 *
 * <p>The governing rule is that nothing here may prevent startup. A missing
 * file is normal, and a broken one degrades to built-in configuration with an
 * explanation rather than an exception.
 */
class UserConfigLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void anAbsentFileIsNormalAndNotAProblem() {
        UserConfigLoader.LoadResult result = loader("").load();

        assertTrue(result.document().isEmpty());
        assertTrue(result.issues().isEmpty(), "absence must not be reported as a problem");
    }

    @Test
    void readsAWellFormedOverlay() throws IOException {
        Path file = write("""
                version: 1
                models:
                  - id: my-qwen
                    provider: ollama
                    providerModelId: qwen2.5:14b
                    defaultOutputTokens: 1600
                    role: MEMBER
                    councilRole: CRITIC
                    modelFamily: qwen
                policies:
                  my-balanced:
                    protocolId: balanced
                    memberModelIds: [local-llama3, my-qwen]
                    chairModelId: local-chair
                profiles:
                  my-council:
                    displayName: My laptop council
                    defaultDepth: BALANCED
                    depthPolicies:
                      BALANCED: my-balanced
                """);

        UserConfigLoader.LoadResult result = loader(file.toString()).load();

        assertFalse(result.hasErrors(), () -> "unexpected issues: " + result.issues());
        assertEquals(1, result.document().models().size());
        assertEquals("qwen2.5:14b", result.document().models().getFirst().providerModelId());
        assertEquals(1, result.document().policies().size());
        assertEquals("My laptop council", result.document().profiles().get("my-council").displayName());
    }

    @Test
    void reportsMalformedYamlWithoutThrowing() throws IOException {
        Path file = write("models:\n  - id: [unclosed\n");

        UserConfigLoader.LoadResult result = loader(file.toString()).load();

        assertTrue(result.hasErrors());
        assertTrue(result.document().isEmpty(), "a broken file falls back to built-in configuration");
    }

    @Test
    void rejectsUnknownFieldsRatherThanIgnoringThem() throws IOException {
        // A silently ignored typo looks exactly like a setting that took effect.
        Path file = write("""
                models:
                  - id: my-qwen
                    provider: ollama
                    providerModelId: qwen2.5:14b
                    temprature: 0.5
                """);

        UserConfigLoader.LoadResult result = loader(file.toString()).load();

        assertTrue(result.hasErrors());
        assertTrue(result.issues().getFirst().message().contains("temprature"),
                   "the error should name the field that was not recognised");
    }

    @Test
    void rejectsAnUnknownTopLevelSection() throws IOException {
        Path file = write("credentialsFile: /etc/keys.yml\n");

        assertTrue(loader(file.toString()).load().hasErrors());
    }

    @Test
    void rejectsAnUnsupportedSchemaVersion() throws IOException {
        Path file = write("version: 99\nmodels: []\n");

        UserConfigLoader.LoadResult result = loader(file.toString()).load();

        assertTrue(result.hasErrors());
        assertTrue(result.issues().getFirst().message().contains("version 99"));
    }

    @Test
    void anEmptyFileIsTreatedAsNoOverlay() throws IOException {
        Path file = write("\n   \n");

        UserConfigLoader.LoadResult result = loader(file.toString()).load();

        assertFalse(result.hasErrors());
        assertTrue(result.document().isEmpty());
    }

    @Test
    void refusesAFileContainingCredentials() throws IOException {
        Path file = write("""
                models:
                  - id: my-model
                    provider: openai
                    providerModelId: gpt-4o
                    apiKey: sk-abcdefghijklmnopqrstuvwxyz123456
                """);

        UserConfigLoader.LoadResult result = loader(file.toString()).load();

        assertTrue(result.hasErrors());
        assertTrue(result.document().isEmpty(), "nothing from a file containing a key is applied");

        // The value must not appear anywhere in what we report — moving it from
        // the user's file into logs and API responses is the outcome being avoided.
        String reported = result.issues().toString();
        assertFalse(reported.contains("sk-abcdefghijklmnopqrstuvwxyz123456"),
                    "the credential value was echoed back");
    }

    @Test
    void defaultsToTheCouncilHomeWhenNoPathIsConfigured() {
        UserConfigLoader loader = new UserConfigLoader(new SecretScanner(), "", tempDir.toString());

        assertEquals(tempDir.resolve(UserConfigLoader.DEFAULT_FILE_NAME), loader.resolvePath());
    }

    private Path write(String content) throws IOException {
        Path file = tempDir.resolve("council-user.yml");
        Files.writeString(file, content);
        return file;
    }

    private UserConfigLoader loader(String path) {
        return new UserConfigLoader(new SecretScanner(), path, tempDir.toString());
    }
}
