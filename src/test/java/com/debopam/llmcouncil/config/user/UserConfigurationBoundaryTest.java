package com.debopam.llmcouncil.config.user;

import com.debopam.llmcouncil.config.ConfigIssue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class UserConfigurationBoundaryTest {
    @TempDir Path temp;

    @Test
    void secretScannerRejectsCredentialFieldsWithoutEchoingValues() {
        SecretScanner scanner = new SecretScanner();
        String secret = "sk-1234567890abcdefghijklmnop";
        var issues = scanner.scan("apiKey: " + secret);

        assertFalse(issues.isEmpty());
        assertTrue(issues.stream().allMatch(issue -> !issue.message().contains(secret)));
        assertTrue(issues.stream().allMatch(issue -> issue.severity() == ConfigIssue.Severity.ERROR));
    }

    @Test
    void secretScannerDoesNotFlagTokenBudgetFields() {
        SecretScanner scanner = new SecretScanner();
        assertTrue(scanner.scan("defaultOutputTokens: 1000\ncontextWindowTokens: 8192").isEmpty());
    }

    @Test
    void loaderIsFailSoftForMalformedAndUnknownConfiguration() throws Exception {
        Path config = temp.resolve("council-user.yml");
        Files.writeString(config, "version: 1\nunknownField: true\n");
        UserConfigLoader loader = new UserConfigLoader(new SecretScanner(), config.toString(), temp.toString());

        var result = loader.load();
        assertTrue(result.hasErrors());
        assertTrue(result.document().isEmpty());
        assertTrue(result.issues().getFirst().message().contains("could not be parsed"));
    }

    @Test
    void loaderTreatsMissingAndBlankFilesAsEmptyConfiguration() throws Exception {
        Path missing = temp.resolve("missing.yml");
        UserConfigLoader missingLoader = new UserConfigLoader(new SecretScanner(), missing.toString(), temp.toString());
        assertFalse(missingLoader.load().hasErrors());
        assertTrue(missingLoader.load().document().isEmpty());

        Path blank = temp.resolve("blank.yml");
        Files.writeString(blank, "   \n");
        UserConfigLoader blankLoader = new UserConfigLoader(new SecretScanner(), blank.toString(), temp.toString());
        assertTrue(blankLoader.load().document().isEmpty());
    }

    @Test
    void unsupportedDocumentVersionIsActionable() throws Exception {
        Path config = temp.resolve("council-user.yml");
        Files.writeString(config, "version: 999\n");
        var result = new UserConfigLoader(new SecretScanner(), config.toString(), temp.toString()).load();

        assertTrue(result.hasErrors());
        assertTrue(result.issues().stream().anyMatch(issue -> "version".equals(issue.field())));
    }
}
