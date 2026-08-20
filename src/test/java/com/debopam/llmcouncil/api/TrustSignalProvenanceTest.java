package com.debopam.llmcouncil.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A trust signal must describe the run, not the configuration as it stands now.
 *
 * <p>Read as text, like {@code DockerComposeConfigurationTest} and
 * {@code ProvidersPanelTest}, because the defect these guard against is a
 * <em>source</em> being wrong rather than a value: the UI once derived dissent
 * preservation by looking the run's protocol up in the live catalog. That
 * produces the right answer in every test and every demo, and the wrong one the
 * moment someone edits a protocol — which the configuration write path made a
 * two-click operation. Every past answer would then be relabelled with settings
 * it never ran under, silently and in the direction of overclaiming.
 *
 * <p>There is no JavaScript test runner in this build, and adding one to assert
 * two lines would cost more than it protects. What matters is caught here.
 */
class TrustSignalProvenanceTest {

    private static final Path MAIN = Path.of("src/main/resources/static/js/main.js");
    private static final Path TRUST = Path.of("src/main/resources/static/js/trust.js");

    @Test
    void dissentPreservationIsReadFromTheRunRatherThanTheCatalog() throws IOException {
        String source = Files.readString(MAIN, StandardCharsets.UTF_8);

        assertTrue(source.contains("result.integrity?.preserveDissent"),
                   "the run reports the value it executed under; the UI must use it");
        assertFalse(source.contains("preserveDissentFor"),
                    "the catalog lookup this replaced relabels finished runs whenever a protocol "
                    + "is edited — it must not come back");
        assertFalse(source.contains("stageOptions?.SYNTHESIZE"),
                    "no trust signal may be derived from the current catalog's stage options");
    }

    @Test
    void theSycophancyPillDistinguishesSuppressionFromACleanResult() throws IOException {
        String source = Files.readString(TRUST, StandardCharsets.UTF_8);

        // Three of these existed already. The fourth is the one that looks most
        // like a pass: detection ran, at a threshold nothing could trip.
        assertTrue(source.contains("\"no sycophancy\""));
        assertTrue(source.contains("\"sycophancy not measured\""));
        assertTrue(source.contains("\"sycophancy barely checked\""),
                   "a run whose threshold suppressed detection must not show the same green pill "
                   + "as one that was checked properly and came back clean");
        assertTrue(source.contains("sycophancySuppressed(result)"),
                   "the suppression check must consult the run, not a constant");
    }

    @Test
    void weakenedGuaranteesAreRenderedAboveTheAnswerRatherThanBesideIt() throws IOException {
        String main = Files.readString(MAIN, StandardCharsets.UTF_8);
        String trust = Files.readString(TRUST, StandardCharsets.UTF_8);

        assertTrue(trust.contains("export function renderIntegrity"));

        // Order is the argument: below the recommendation these become footnotes
        // to a conclusion the reader has already accepted.
        int integrity = main.indexOf("renderIntegrity(result)");
        int prose = main.indexOf("renderMarkdown(body)");
        assertTrue(integrity > 0 && prose > 0 && integrity < prose,
                   "weakened checks must render before the answer prose");
    }

    @Test
    void validatorConfidenceAndIndependenceAreNotPresentedAsExternalFactChecking() throws IOException {
        String trust = Files.readString(TRUST, StandardCharsets.UTF_8);
        String artifacts = Files.readString(
                Path.of("src/main/resources/static/js/artifacts.js"), StandardCharsets.UTF_8);

        assertTrue(trust.contains("validator confidence"));
        assertTrue(trust.contains("validator independent of council producers"));
        assertTrue(trust.contains("validator correlated with a council producer"));
        assertTrue(trust.contains("chair self-validation"));
        assertTrue(artifacts.contains("not external fact-checking or human review"));
    }
}
