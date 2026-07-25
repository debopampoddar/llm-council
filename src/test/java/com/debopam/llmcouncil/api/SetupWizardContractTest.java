package com.debopam.llmcouncil.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads the wizard's source as text and checks the things behaviour cannot.
 *
 * <p>The precedent is {@code TrustSignalProvenanceTest}: the catalog lookup it
 * replaced returned the right answer in every test and demo and the wrong one in
 * production, so a behavioural test would not have caught it. The same shape of
 * problem lives here. A behavioural test can show that <em>this</em> path asks
 * for an acknowledgement; it cannot show that no other path skips it, and "no
 * other path" is the whole guarantee.
 *
 * <p>These are crude guards and they are honest about it. Every one of them is
 * paired with a fixture that the same check flags, so a regex that quietly
 * stopped matching anything fails here rather than passing everywhere.
 */
class SetupWizardContractTest {

    private static final Path STATIC = Path.of("src/main/resources/static");

    /** How much context before a call site counts as guarding it. */
    private static final int GUARD_WINDOW = 600;

    // ── The description does not leave the machine unacknowledged ───────

    @Test
    void thereIsExactlyOneWayForThisPageToSendADescriptionToAModel() throws IOException {
        assertEquals(1, occurrences(read("js/setup.js"), "advisorApi.extract("),
                     "a second call site is a second chance to skip the acknowledgement; "
                     + "keep extraction behind extractAfterAcknowledgement()");
    }

    @Test
    void thatCallSiteIsGuardedByTheAcknowledgement() throws IOException {
        String source = read("js/setup.js");

        assertTrue(precedingText(source, "advisorApi.extract(").contains("acknowledged"),
                   "the only extraction call must be preceded by the acknowledgement check");
    }

    @Test
    void theAcknowledgementGuardWouldNoticeAnUnguardedCall() {
        // Positive control. Without it, the assertion above passes just as well
        // if precedingText() silently returned the whole file.
        String unguarded = "async function readIt() {\n"
                           + "  const text = state.text;\n"
                           + "  return advisorApi.extract(text, state.modelId, true);\n}";

        assertFalse(precedingText(unguarded, "advisorApi.extract(").contains("acknowledged"),
                    "the check must be able to fail");
    }

    @Test
    void theWizardOffersNoFreeTextFieldThatCouldSupplyAModelId() throws IOException {
        String source = read("js/setup.js");

        // The model is chosen from a <select> built from the environment, so the
        // id submitted is always one the server offered. A text input would be a
        // route from typing to a model that was never listed.
        assertTrue(source.contains("el(\"select.wz-select\""),
                   "the model picker must be a select");
        assertFalse(source.contains("type: \"text\""),
                    "no free-text input belongs on this page: the only text a user types is "
                    + "the description, which is a textarea and is never used as an id");
    }

    @Test
    void aCloudModelIsNamedBeforeAnythingIsSent() throws IOException {
        String source = read("js/setup.js");

        assertTrue(source.contains("renderCloudAcknowledgement"),
                   "the provider a description would reach has to be named, not implied");
        assertTrue(source.contains("leaves this computer"),
                   "and said in words rather than as a checkbox label alone");
    }

    // ── Writing configuration shows what is lost first ──────────────────

    @Test
    void thereIsExactlyOneWayForThisPageToWriteConfiguration() throws IOException {
        assertEquals(1, occurrences(read("js/setup.js"), "advisorApi.applyConfiguration("),
                     "one write path, gated once");
    }

    @Test
    void thatWriteIsGuardedByHavingShownTheRemovalList() throws IOException {
        String guard = precedingText(read("js/setup.js"), "advisorApi.applyConfiguration(");

        assertTrue(guard.contains("state.confirming"),
                   "the write must be reachable only from the confirmation panel");
        assertTrue(guard.contains("removalsOf"),
                   "and that panel is the one that renders what would be removed");
    }

    @Test
    void theWriteGuardWouldNoticeAnUnguardedCall() {
        // Positive control for the pair above.
        String unguarded = "async function save() {\n"
                           + "  return advisorApi.applyConfiguration(state.synthesis.document);\n}";

        assertFalse(precedingText(unguarded, "advisorApi.applyConfiguration(")
                            .contains("state.confirming"),
                    "the check must be able to fail");
    }

    @Test
    void theConfirmationSaysTheFileIsReplacedRatherThanMerged() throws IOException {
        assertTrue(read("js/setup.js").contains("configuration file is replaced"),
                   "the write path replaces the whole overlay; a user confirming it should be "
                   + "told that, not left to infer it from a kept backup");
    }

    // ── Nothing here is parsed as markup ───────────────────────────────

    @Test
    void noWizardModuleUsesInnerHtml() throws IOException {
        for (String module : new String[]{"js/setup.js", "js/advisor-api.js",
                                          "js/requirement-form.js", "js/proposal.js"}) {
            assertFalse(read(module).contains("innerHTML"),
                        module + " builds nodes; a model's output and a user's own description "
                        + "both reach this page and neither may be parsed as markup");
        }
    }

    @Test
    void theInnerHtmlCheckWouldNoticeOne() {
        assertTrue("node.innerHTML = value;".contains("innerHTML"), "the check must be able to fire");
    }

    // ── Absent signals stay distinguishable ────────────────────────────

    @Test
    void theProposalNoticeTellsBrokenApartFromStale() throws IOException {
        String source = read("js/proposal.js");

        // Two different claims about a saved proposal, and they call for
        // different answers: one cannot be applied at all, the other can and
        // might still be what the user wants.
        assertTrue(source.contains("validation") && source.contains("valid"),
                   "broken must be read from the validation report");
        assertTrue(source.contains("resynthesisDiffers"),
                   "stale must be read from the re-synthesis comparison, not inferred from "
                   + "validation");
    }

    @Test
    void theFirstRunPointerOnlyAppearsWhenNothingIsConfigured() throws IOException {
        assertTrue(read("js/proposal.js").contains("hasUserConfiguration"),
                   "advice offered to somebody whose setup is fine reads as a warning, and a "
                   + "warning that never clears is one people learn to ignore");
    }

    // ── The inert control admits it is inert ───────────────────────────

    @Test
    void theSubjectControlSaysItDoesNotChangeModelSelection() throws IOException {
        assertTrue(read("js/requirement-form.js").contains("does not change which"),
                   "a control that silently changes nothing is worse than one that admits it: "
                   + "the user would assume the council was tuned for their subject");
    }

    // ── The notice survives a re-render ────────────────────────────────

    @Test
    void theConfigNoticeLivesOutsideTheElementThatIsReplacedOnEveryRender() throws IOException {
        String html = read("index.html");

        int notice = html.indexOf("id=\"config-notice\"");
        int streamInner = html.indexOf("id=\"stream-inner\"");

        assertTrue(notice > 0, "the notice element must exist");
        assertTrue(notice < streamInner,
                   "renderStream() replaces every child of stream-inner, so a notice inside it "
                   + "would vanish on the next keystroke");
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private String read(String relative) throws IOException {
        Path path = STATIC.resolve(relative).normalize();
        return Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : "";
    }

    private int occurrences(String source, String needle) {
        int count = 0;
        for (int index = source.indexOf(needle); index >= 0;
             index = source.indexOf(needle, index + needle.length())) {
            count++;
        }
        return count;
    }

    /** The window of source immediately before a call site. */
    private String precedingText(String source, String needle) {
        int index = source.indexOf(needle);
        if (index < 0) {
            return "";
        }
        return source.substring(Math.max(0, index - GUARD_WINDOW), index);
    }
}
