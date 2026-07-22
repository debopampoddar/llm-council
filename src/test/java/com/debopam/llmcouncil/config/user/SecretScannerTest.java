package com.debopam.llmcouncil.config.user;

import com.debopam.llmcouncil.config.ConfigIssue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers refusal of credential material in the overlay.
 *
 * <p>Two properties matter: offences are caught, and the offending value is
 * never repeated back. Echoing it would move the secret out of a file the user
 * controls and into logs and API responses, which is what this prevents.
 */
class SecretScannerTest {

    private final SecretScanner scanner = new SecretScanner();

    @ParameterizedTest
    @ValueSource(strings = {
            "apiKey: something",
            "api_key: something",
            "api-key: something",
            "  secret: something",
            "password: hunter2",
            "clientSecret: abc",
            "  - accessToken: abc",
            "privateKey: abc"
    })
    void rejectsCredentialFields(String line) {
        assertFalse(scanner.scan("models:\n" + line + "\n").isEmpty(),
                    "should have rejected: " + line);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "defaultOutputTokens: 1200",
            "contextWindowTokens: 8192",
            "chatRecentTurnCount: 4",
            "retryMaxAttempts: 2",
            "providerModelId: qwen2.5:14b"
    })
    void acceptsLegitimateFieldsThatContainSimilarWords(String line) {
        // 'defaultOutputTokens' contains 'token'. A loose pattern would reject
        // the ordinary configuration this feature exists to enable.
        assertTrue(scanner.scan("models:\n  - " + line + "\n").isEmpty(),
                   "should have accepted: " + line);
    }

    @Test
    void detectsKeyShapedValuesEvenUnderAnInnocentFieldName() {
        List<ConfigIssue> issues = scanner.scan(
                "models:\n  - providerModelId: sk-abcdefghijklmnopqrstuvwxyz012345\n");

        assertFalse(issues.isEmpty());
        assertTrue(issues.getFirst().remediation().contains("rotate"),
                   "a key written to disk in plain text should be treated as compromised");
    }

    @Test
    void neverEchoesTheSecret() {
        String secret = "sk-ant-abcdefghijklmnopqrstuvwxyz012345";

        String reported = scanner.scan("apiKey: " + secret + "\n").toString();

        assertFalse(reported.contains(secret), "the scanner leaked the value it was rejecting");
    }

    @Test
    void reportsTheLineSoTheUserCanFindIt() {
        List<ConfigIssue> issues = scanner.scan("version: 1\nmodels:\n  - id: a\n    apiKey: x\n");

        assertTrue(issues.getFirst().message().contains("line 4"),
                   "message should locate the offence: " + issues.getFirst().message());
    }

    @Test
    void acceptsACleanFile() {
        assertTrue(scanner.scan("""
                version: 1
                models:
                  - id: my-qwen
                    provider: ollama
                    providerModelId: qwen2.5:14b
                """).isEmpty());
    }
}
