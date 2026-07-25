package com.debopam.llmcouncil.advisor;

import com.debopam.llmcouncil.api.dto.CatalogDiffResponse;
import com.debopam.llmcouncil.application.ConfigDraftService;
import com.debopam.llmcouncil.config.user.UserConfigDocument;
import com.debopam.llmcouncil.config.user.UserConfigDocument.UserModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The advisor adds. It does not replace configuration it did not write.
 *
 * <p>This matters because {@code PUT /api/council/config/draft} replaces the
 * whole overlay file. Without the carry-forward rule, running the wizard once
 * would silently delete a council somebody had hand-written, and the only thing
 * standing between them and that loss would be a confirmation dialog and a
 * {@code .bak} — a backstop, not a substitute for not doing it.
 *
 * <p>Boots against a real hand-written overlay so the claim is tested end to
 * end: the document is read from disk by the same loader startup uses, extended,
 * and previewed against the catalog that overlay actually produced.
 */
@SpringBootTest
@TestPropertySource(properties =
        "council.userConfigPath=src/test/resources/user-config/hand-written.yml")
class AdvisorIsAdditiveTest {

    private static final List<String> TWO_FAMILIES = List.of("llama3.1:8b", "mistral:7b");

    @Autowired
    private AdvisorService advisor;

    @Autowired
    private ConfigDraftService draftService;

    @Autowired
    private AdvisorEnvironmentService environmentService;

    @Test
    void theHandWrittenConfigurationIsActuallyThere() {
        // Everything below is about preserving this. If the fixture stopped
        // loading, every preservation assertion would pass over an empty file.
        UserConfigDocument draft = draftService.draft();

        assertEquals(List.of("my-critic"), draft.models().stream().map(UserModel::id).toList());
        assertTrue(draft.policies().containsKey("my-policy"));
        assertTrue(draft.profiles().containsKey("my-council"));
    }

    @Test
    void synthesisCarriesEveryHandWrittenEntityThrough() {
        UserConfigDocument result = synthesise().document();

        assertTrue(result.models().stream().anyMatch(model -> model.id().equals("my-critic")),
                   "a hand-written model must survive: " + result.models());
        assertEquals(draftService.draft().policies().get("my-policy"),
                     result.policies().get("my-policy"),
                     "and unchanged, not merely present");
        assertEquals(draftService.draft().profiles().get("my-council"),
                     result.profiles().get("my-council"));
        assertEquals(draftService.draft().runtime(), result.runtime(),
                     "the advisor has no opinion about runtime knobs");
    }

    @Test
    void theSynthesisedConfigurationRemovesNothing() {
        List<CatalogDiffResponse.EntityChange> removals = removals(synthesise().document());

        assertTrue(removals.isEmpty(),
                   "running the advisor must never cost a user an entity they wrote: " + removals);
    }

    @Test
    void aDocumentThatDroppedTheHandWrittenModelWouldReportItAsRemoved() {
        // Positive control. Without this, the assertion above passes just as well
        // if the diff never reported removals at all — which is exactly the bug
        // it is meant to catch.
        UserConfigDocument dropped = new UserConfigDocument(
                UserConfigDocument.SUPPORTED_VERSION, List.of(),
                draftService.draft().policies(), draftService.draft().profiles(),
                draftService.draft().protocols(), draftService.draft().runtime());

        assertTrue(removals(dropped).stream()
                                    .anyMatch(change -> change.type().equals("model")
                                                        && change.id().equals("my-critic")),
                   "the detector must be able to fire: " + removals(dropped));
    }

    @Test
    void aHandWrittenModelIsAvailableToBeSeated() {
        // Carrying the user's models forward is what makes selecting them safe:
        // they survive the write, so a policy referencing one still resolves.
        SynthesisResult result = synthesise();

        assertTrue(advisor.validate(result.document()).valid(),
                   "the extended document must still validate: "
                   + advisor.validate(result.document()).issues());
    }

    @Test
    void aSecondRunReplacesTheAdvisorsOwnOutputAndNothingElse() {
        UserConfigDocument first = synthesise().document();

        SynthesisResult second = advisor.synthesize(
                new CouncilRequirement(CouncilRequirement.Privacy.LOCAL_ONLY,
                                       CouncilRequirement.Latency.MODERATE,
                                       CouncilRequirement.Cost.FREE_ONLY,
                                       CouncilRequirement.Rigor.QUICK, 2,
                                       Set.of(CouncilRequirement.Domain.GENERAL), false),
                environmentService.describe(TWO_FAMILIES), false);

        // The second run starts from what is on disk, not from `first`, so the
        // advisor entities in `first` are not in play. What matters is that the
        // user's entities are in both and the advisor's profile appears once.
        assertTrue(first.profiles().containsKey("my-council"));
        assertTrue(second.document().profiles().containsKey("my-council"));
        assertEquals(1, second.document().profiles().keySet().stream()
                               .filter(AdvisorIds::owns).count(),
                     "exactly one advisor profile: " + second.document().profiles().keySet());
        assertFalse(removals(second.document()).stream()
                            .anyMatch(change -> change.id().equals("my-critic")));
    }

    private SynthesisResult synthesise() {
        return advisor.synthesize(
                new CouncilRequirement(CouncilRequirement.Privacy.LOCAL_ONLY,
                                       CouncilRequirement.Latency.MODERATE,
                                       CouncilRequirement.Cost.FREE_ONLY,
                                       CouncilRequirement.Rigor.BALANCED, 3,
                                       Set.of(CouncilRequirement.Domain.GENERAL), false),
                environmentService.describe(TWO_FAMILIES), false);
    }

    private List<CatalogDiffResponse.EntityChange> removals(UserConfigDocument document) {
        return advisor.preview(document).changes().stream()
                      .filter(change -> change.change() == CatalogDiffResponse.Change.REMOVED)
                      .toList();
    }
}
