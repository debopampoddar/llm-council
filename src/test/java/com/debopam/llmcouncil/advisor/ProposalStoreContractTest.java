package com.debopam.llmcouncil.advisor;

import com.debopam.llmcouncil.config.user.UserConfigDocument;
import com.debopam.llmcouncil.config.user.UserConfigDocument.UserModel;
import com.debopam.llmcouncil.config.user.UserConfigDocument.UserPolicy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What every {@link ProposalStore} must do, whatever it stores in.
 *
 * <p>There is one implementation today. This is written as a contract anyway so
 * that if the multi-user question is ever answered differently, the answer is a
 * second implementation and a passing subclass rather than an excavation of what
 * the first one happened to guarantee.
 */
abstract class ProposalStoreContractTest {

    /** @return the store under test, empty at the start of each test */
    protected abstract ProposalStore store();

    @Test
    void nothingIsSavedToBeginWith() {
        assertTrue(store().load().isEmpty());
    }

    @Test
    void aSavedProposalComesBackWhole() {
        store().save(proposal("advisor-first", CouncilRequirement.Rigor.RIGOROUS));

        ProposalEnvelope loaded = store().load().orElseThrow();

        assertEquals(ProposalEnvelope.KIND, loaded.kind());
        assertTrue(loaded.valid());
        assertEquals(CouncilRequirement.Rigor.RIGOROUS, loaded.requirement().rigor());
        assertEquals(CouncilRequirement.Privacy.LOCAL_ONLY, loaded.requirement().privacy());
        assertEquals(2, loaded.requirement().councilSize());
        assertEquals(Set.of(CouncilRequirement.Domain.CODE), loaded.requirement().domains());
        assertEquals(List.of("advisor-first"),
                     loaded.document().models().stream().map(UserModel::id).toList());
        assertEquals(List.of("Because."), loaded.rationale());
        assertNotNull(loaded.savedAtInstant(), "the timestamp must survive the round trip");
    }

    @Test
    void thereIsOnlyEverOneProposal() {
        store().save(proposal("advisor-first", CouncilRequirement.Rigor.QUICK));
        store().save(proposal("advisor-second", CouncilRequirement.Rigor.RIGOROUS));

        ProposalEnvelope loaded = store().load().orElseThrow();

        assertEquals(List.of("advisor-second"),
                     loaded.document().models().stream().map(UserModel::id).toList(),
                     "saving replaces; a proposal is something you are in the middle of, "
                     + "not a record of what you did");
    }

    @Test
    void discardingRemovesIt() {
        store().save(proposal("advisor-first", CouncilRequirement.Rigor.BALANCED));
        assertTrue(store().load().isPresent(), "there must be something to discard");

        assertTrue(store().discard());
        assertTrue(store().load().isEmpty());
    }

    @Test
    void discardingNothingIsNotAnError() {
        assertFalse(store().discard(), "a discard with nothing saved reports that, not a failure");
    }

    @Test
    void theLocationIsReportedWhetherOrNotOneIsSaved() {
        assertNotNull(store().location(), "a user should be able to see where this lives");

        store().save(proposal("advisor-first", CouncilRequirement.Rigor.BALANCED));
        assertNotNull(store().location());
    }

    /** A proposal carrying one recognisable model and one policy. */
    protected static ProposalEnvelope proposal(String modelId, CouncilRequirement.Rigor rigor) {
        UserConfigDocument document = new UserConfigDocument(
                UserConfigDocument.SUPPORTED_VERSION,
                List.of(new UserModel(modelId, "ollama", "canary:1b", 1200, 0.3, 240, null,
                                      "MEMBER", "PROPOSER", "canary", null, null, null, null)),
                Map.of(AdvisorIds.BALANCED_POLICY, new UserPolicy(
                        "balanced", List.of(modelId), modelId, null, 1, 0, false, true, null)),
                Map.of(), Map.of(), null);

        CouncilRequirement requirement = new CouncilRequirement(
                CouncilRequirement.Privacy.LOCAL_ONLY, CouncilRequirement.Latency.MODERATE,
                CouncilRequirement.Cost.FREE_ONLY, rigor, 2,
                Set.of(CouncilRequirement.Domain.CODE), true);

        return ProposalEnvelope.of(requirement,
                                   new SynthesisResult(document, AdvisorIds.PROFILE,
                                                       List.of("Because."), List.of()),
                                   Instant.parse("2026-07-24T09:00:00Z"));
    }
}
