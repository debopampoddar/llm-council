package com.debopam.llmcouncil.advisor;

import com.debopam.llmcouncil.api.dto.CatalogDiffResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Saving a council for later, and what reading it back is allowed to claim.
 *
 * <p>The rule this exists for: a proposal is re-checked when it is <b>read</b>,
 * not only when it is saved. A proposal written three weeks ago may name a model
 * that has since been deleted, and offering it for one click of "apply" on the
 * strength of a check that passed three weeks ago is how somebody ends up with a
 * council that cannot run.
 *
 * <p>Broken and stale are separate answers here, because validation cannot see
 * staleness: a proposal naming built-in model ids still validates perfectly after
 * the models behind them are uninstalled, since validation resolves against the
 * catalog and not against the runtime.
 */
@SpringBootTest
@TestPropertySource(properties =
        "council.userConfigPath=target/advisor-proposal-lifecycle/council-user.yml")
class ProposalLifecycleTest {

    private static final List<String> TWO_FAMILIES = List.of("llama3.1:8b", "mistral:7b");

    @Autowired
    private AdvisorService advisor;

    @Autowired
    private AdvisorEnvironmentService environmentService;

    @Autowired
    private ProposalStore store;

    @BeforeEach
    @AfterEach
    void clearAnyPreviousProposal() {
        store.discard();
    }

    @Test
    void nothingIsProposedUntilSomethingIsSaved() {
        StoredProposal proposal = advisor.proposal();

        assertFalse(proposal.present());
        assertNotNull(proposal.location(), "a user should still be told where one would live");
    }

    @Test
    void savingProducesAProposalThatIsCheckedRightAway() {
        StoredProposal proposal = save();

        assertTrue(proposal.present());
        assertEquals(CouncilRequirement.Rigor.RIGOROUS, proposal.requirement().rigor());
        assertNotNull(proposal.validation(), "a proposal is never offered without its check");
        assertTrue(proposal.validation().valid(), "got " + proposal.validation().issues());
        assertNotNull(proposal.savedAt());
    }

    @Test
    void aSavedProposalIsRecheckedOnEveryRead() {
        save();

        StoredProposal reread = advisor.proposal(environmentService.describe(TWO_FAMILIES));

        assertTrue(reread.present());
        assertNotNull(reread.validation(),
                      "the check must be computed on read, not stored with the proposal");
        assertNotNull(reread.preview(), "and so must the diff");
    }

    @Test
    void applyingAProposalWouldRemoveNothing() {
        List<CatalogDiffResponse.EntityChange> removals = save().preview().changes().stream()
                .filter(change -> change.change() == CatalogDiffResponse.Change.REMOVED)
                .toList();

        assertTrue(removals.isEmpty(), "the advisor only adds: " + removals);
    }

    @Test
    void savingDoesNotApply() {
        save();

        assertTrue(advisor.environment().catalogModels().stream()
                          .noneMatch(model -> AdvisorIds.owns(model.id())),
                   "a saved proposal must not become part of the running configuration");
    }

    @Test
    void aProposalIsStaleOnceTheMachineCanNoLongerSeatItsCouncil() {
        save();

        // Read back against a machine with nothing installed — the models the
        // proposal names have been deleted since it was saved.
        StoredProposal reread = advisor.proposal(environmentService.describe(List.of()));

        assertTrue(reread.resynthesisDiffers(),
                   "a proposal whose models cannot be run today is stale, whatever validation says");
        assertTrue(reread.resynthesisNote().contains("cannot seat"),
                   "and the reason must be legible: " + reread.resynthesisNote());
        assertTrue(reread.validation().valid(),
                   "validation alone calls this fine — the ids still resolve against the catalog, "
                   + "which is exactly why staleness is a separate answer");
    }

    @Test
    void anUnchangedMachineReportsNoStaleness() {
        // Control for the test above: staleness tracks the machine, not every
        // read. Without this, "differs" could be hard-coded true and pass.
        save();

        StoredProposal reread = advisor.proposal(environmentService.describe(TWO_FAMILIES));

        assertFalse(reread.resynthesisDiffers(),
                    "nothing changed, so re-running the advisor would produce the same council: "
                    + reread.resynthesisNote());
    }

    @Test
    void aNewlyInstalledModelMakesAProposalStale() {
        // The other direction, and the one a user most wants told: they pulled a
        // second family after saving, and the saved council no longer reflects
        // the best this machine can do.
        advisor.saveProposal(requirement(),
                             environmentService.describe(List.of("llama3.1:8b")), false);

        StoredProposal reread = advisor.proposal(environmentService.describe(TWO_FAMILIES));

        assertTrue(reread.resynthesisDiffers(), "got " + reread.resynthesisNote());
        assertTrue(reread.resynthesisNote().contains("would now use"),
                   "the difference must be named: " + reread.resynthesisNote());
    }

    @Test
    void discardingLeavesNothingToResume() {
        save();
        assertTrue(advisor.proposal().present(), "there must be something to discard");

        assertTrue(advisor.discardProposal());
        assertFalse(advisor.proposal().present());
    }

    @Test
    void savingWhatCannotBeSeatedRefusesWithSomethingToDoAboutIt() {
        AdvisorRequestException refusal = assertThrows(AdvisorRequestException.class, () ->
                advisor.saveProposal(requirement(), environmentService.describe(List.of()), false));

        assertTrue(refusal.getMessage().contains("no local models installed"), refusal.getMessage());
        assertTrue(refusal.remediation().contains("ollama pull"), refusal.remediation());
        assertFalse(advisor.proposal().present(), "and nothing is saved");
    }

    @Test
    void aStoredProposalKeepsTheRequirementSoStalenessCanBeAnswered() {
        save();

        CouncilRequirement stored = advisor.proposal().requirement();

        assertEquals(Set.of(CouncilRequirement.Domain.CODE), stored.domains());
        assertTrue(stored.adversarialEmphasis());
        assertTrue(stored.localOnly());
    }

    private StoredProposal save() {
        return advisor.saveProposal(requirement(), environmentService.describe(TWO_FAMILIES), false);
    }

    private CouncilRequirement requirement() {
        return new CouncilRequirement(CouncilRequirement.Privacy.LOCAL_ONLY,
                                      CouncilRequirement.Latency.MODERATE,
                                      CouncilRequirement.Cost.FREE_ONLY,
                                      CouncilRequirement.Rigor.RIGOROUS, 3,
                                      Set.of(CouncilRequirement.Domain.CODE), true);
    }
}
