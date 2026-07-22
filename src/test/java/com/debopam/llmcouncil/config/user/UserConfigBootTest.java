package com.debopam.llmcouncil.config.user;

import com.debopam.llmcouncil.api.dto.CatalogResponse;
import com.debopam.llmcouncil.application.CatalogService;
import com.debopam.llmcouncil.config.ConfigIssue;
import com.debopam.llmcouncil.config.ConfigOrigin;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots the application against a deliberately half-broken overlay.
 *
 * <p>The unit tests prove each rule; this proves the whole path is connected and,
 * more importantly, that a user's mistake cannot stop the application starting.
 * One bad entity is dropped and reported while everything else — including the
 * shipped configuration — still works.
 */
@SpringBootTest
@TestPropertySource(properties =
        "council.userConfigPath=src/test/resources/user-config/partially-invalid.yml")
class UserConfigBootTest {

    @Autowired
    private CatalogService catalogService;

    @Test
    void theApplicationStartsDespiteAnInvalidEntity() {
        // Reaching this assertion at all is the point: context startup did not
        // fail on a user file containing an error.
        assertTrue(catalog().generation() > 1, "the overlay should have produced a new generation");
    }

    @Test
    void validUserEntitiesAreApplied() {
        List<CatalogResponse.ModelSummary> models = catalogService
                .catalog(Set.of("models"), true).models();

        CatalogResponse.ModelSummary added = models.stream()
                .filter(model -> model.id().equals("my-critic"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("user-defined model was not applied"));

        assertEquals("ollama", added.provider());
        assertEquals(ConfigOrigin.USER, added.origin());
    }

    @Test
    void aUserProfileBecomesSelectable() {
        List<CatalogResponse.ProfileSummary> profiles = catalogService
                .catalog(Set.of("profiles"), true).profiles();

        assertTrue(profiles.stream().anyMatch(profile -> profile.id().equals("my-council")),
                   "a valid user profile should appear alongside the built-in ones");
    }

    @Test
    void theInvalidEntityIsDroppedAndExplained() {
        List<ConfigIssue> issues = catalog().issues();

        assertTrue(issues.stream().anyMatch(issue ->
                           issue.severity() == ConfigIssue.Severity.ERROR
                           && issue.entityKey().equals("model:bad-temperature")),
                   "the invalid model should be reported: " + issues);

        assertTrue(catalogService.catalog(Set.of("models"), true).models().stream()
                                 .noneMatch(model -> model.id().equals("bad-temperature")),
                   "and must not be present in the catalog");
    }

    @Test
    void builtInConfigurationSurvivesAlongsideTheOverlay() {
        CatalogResponse response = catalogService.catalog(Set.of("profiles", "models"), true);

        assertTrue(response.profiles().stream().anyMatch(profile -> profile.id().equals("local")),
                   "shipped profiles must still be available");
        assertTrue(response.models().stream().anyMatch(model -> model.id().equals("local-chair")),
                   "shipped models must still be available");
    }

    @Test
    void issuesAreReportedThroughTheCatalogApi() {
        // The startup log scrolls away; the catalog endpoint is where a user or
        // UI can still find out what was rejected.
        assertTrue(catalogService.catalog(Set.of("issues"), true).issues().stream()
                                 .anyMatch(issue -> issue.severity() == ConfigIssue.Severity.ERROR));
    }

    private com.debopam.llmcouncil.api.dto.CatalogResponse catalog() {
        return catalogService.catalog(Set.of("issues"), true);
    }
}
