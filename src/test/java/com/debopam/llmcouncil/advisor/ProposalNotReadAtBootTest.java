package com.debopam.llmcouncil.advisor;

import com.debopam.llmcouncil.application.CatalogService;
import com.debopam.llmcouncil.config.ConfigIssue;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A saved proposal is never live configuration.
 *
 * <p>The riskiest new guarantee in the advisor, and the easiest to test badly.
 * "The canary model is absent from the catalog" passes for the right reason
 * only if the overlay loader would otherwise have found it — and passes just as
 * well if the loader is broken, if the fixture is malformed, or if the model id
 * is misspelled in the assertion. So all three cases below use the <b>same</b>
 * canary model, and two of them are controls.
 *
 * <ol>
 *   <li>A proposal sitting where the store puts one: absent from the catalog.</li>
 *   <li>Its inner document written as an overlay: <b>present</b>. This is what
 *       makes case 1 mean something.</li>
 *   <li>The proposal file copied verbatim over the overlay: absent, <b>and</b>
 *       refused with an issue naming {@code kind}. This is what makes the marker
 *       mean something — without it, case 1 would pass on the filename alone,
 *       and a user who copied the file to the wrong name would silently get live
 *       configuration.</li>
 * </ol>
 */
class ProposalNotReadAtBootTest {

    private static final String CANARY = "proposal-canary-model";

    /**
     * Case 1: a saved proposal next to an overlay that does not exist.
     *
     * <p>{@code council-user.yml} is absent from that directory; only the
     * proposal is there.
     */
    @Nested
    @SpringBootTest
    @TestPropertySource(properties =
            "council.userConfigPath=src/test/resources/proposal-boot/council-user.yml")
    class WithOnlyAProposalOnDisk {

        @Autowired
        private CatalogService catalogService;

        @Test
        void theProposalsModelIsNotInTheCatalog() {
            assertFalse(modelIds(catalogService).contains(CANARY),
                        "a saved proposal must not become configuration by being on disk");
        }

        @Test
        void andNothingIsReportedAsWrong() {
            // A proposal sitting beside the configuration is the normal state,
            // not a problem to warn about.
            assertTrue(catalogService.catalog(Set.of("issues"), true).issues().stream()
                                     .noneMatch(issue -> issue.message().contains("proposal")),
                       "an unapplied proposal is not a configuration error");
        }
    }

    /** Case 2: the control. The same model, in a file the loader is meant to read. */
    @Nested
    @SpringBootTest
    @TestPropertySource(properties =
            "council.userConfigPath=src/test/resources/proposal-boot/as-overlay.yml")
    class WithTheSameDocumentAsAnOverlay {

        @Autowired
        private CatalogService catalogService;

        @Test
        void theSameModelIsInTheCatalog() {
            assertTrue(modelIds(catalogService).contains(CANARY),
                       "the overlay loader must actually load this model, or the absence "
                       + "asserted in the other cases proves nothing");
        }
    }

    /**
     * Case 3: the marker.
     *
     * <p>The proposal file copied verbatim to the overlay path — the mistake
     * somebody makes at 2am when a council stops working.
     */
    @Nested
    @SpringBootTest
    @TestPropertySource(properties =
            "council.userConfigPath=src/test/resources/proposal-boot/envelope-copied-to-overlay.yml")
    class WithAProposalCopiedOverTheOverlay {

        @Autowired
        private CatalogService catalogService;

        @Test
        void theModelStillDoesNotReachTheCatalog() {
            assertFalse(modelIds(catalogService).contains(CANARY),
                        "renaming a proposal must not turn it into configuration");
        }

        @Test
        void andTheReasonNamesTheMarkerRatherThanFailingSilently() {
            List<ConfigIssue> issues = catalogService.catalog(Set.of("issues"), true).issues();

            assertTrue(issues.stream().anyMatch(issue ->
                               issue.severity() == ConfigIssue.Severity.ERROR
                               && issue.message().contains("kind")),
                       "the refusal must say what made the file unusable: " + issues);
        }

        @Test
        void andTheApplicationStillStarts() {
            // Reaching this at all is the assertion: a file that is not
            // configuration must not stop the application, only be refused.
            assertTrue(catalogService.catalog(Set.of("profiles"), true).profiles().stream()
                                     .anyMatch(profile -> profile.id().equals("local")),
                       "shipped configuration must still be available");
        }
    }

    private static List<String> modelIds(CatalogService catalogService) {
        return catalogService.catalog(Set.of("models"), true).models().stream()
                             .map(model -> model.id())
                             .toList();
    }
}
