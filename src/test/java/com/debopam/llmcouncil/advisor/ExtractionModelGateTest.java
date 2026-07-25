package com.debopam.llmcouncil.advisor;

import com.debopam.llmcouncil.advisor.AdvisorEnvironment.CandidateModel;
import com.debopam.llmcouncil.model.ClientAvailability;
import com.debopam.llmcouncil.model.CouncilRole;
import com.debopam.llmcouncil.model.ModelRole;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which model may be asked to read a description, decided on the server.
 *
 * <p>The wizard asks the user to confirm before a description leaves the
 * machine, but a confirmation implemented only in a web page is not a control —
 * the endpoint is reachable without it, and a command-line caller would never
 * see it at all. These are the refusals themselves.
 *
 * <p>Tested against hand-built environments rather than through Spring, because
 * the interesting case is an active cloud provider and no hermetic test can
 * produce one: the shipped configuration ships placeholder credentials, so every
 * cloud client in a test run is unavailable.
 */
class ExtractionModelGateTest {

    @Test
    void aLocalModelNeedsNoAcknowledgement() {
        CandidateModel chosen = AdvisorService.requireUsableExtractionModel(
                environment(local("local-chair"), cloud("openai-gpt")), "local-chair", false);

        assertEquals("local-chair", chosen.id());
    }

    @Test
    void aCloudModelIsRefusedWithoutAnAcknowledgement() {
        AdvisorRequestException refusal = assertThrows(AdvisorRequestException.class, () ->
                AdvisorService.requireUsableExtractionModel(
                        environment(local("local-chair"), cloud("openai-gpt")), "openai-gpt", false));

        assertTrue(refusal.getMessage().contains("openai"),
                   "the provider the description would reach must be named: " + refusal.getMessage());
        assertTrue(refusal.remediation().contains("runs on this machine"),
                   "and the local alternative offered: " + refusal.remediation());
    }

    @Test
    void theSameCloudModelIsAllowedOnceAcknowledged() {
        // Control: the refusal above is the acknowledgement, not the model being
        // unusable for some other reason.
        CandidateModel chosen = AdvisorService.requireUsableExtractionModel(
                environment(local("local-chair"), cloud("openai-gpt")), "openai-gpt", true);

        assertEquals("openai-gpt", chosen.id());
    }

    @Test
    void anIdTheEnvironmentDoesNotOfferIsRefused() {
        // The id is matched against the list, never interpreted. This is what
        // stops a description redirecting extraction at something else: there is
        // no path from free text to a model that was not offered.
        AdvisorRequestException refusal = assertThrows(AdvisorRequestException.class, () ->
                AdvisorService.requireUsableExtractionModel(
                        environment(local("local-chair")), "gpt-4o", false));

        assertTrue(refusal.getMessage().contains("gpt-4o"));
        assertTrue(refusal.remediation().contains("local-chair"),
                   "the caller should be told what it may use: " + refusal.remediation());
    }

    @Test
    void anAcknowledgementDoesNotSmuggleInAnUnofferedModel() {
        assertThrows(AdvisorRequestException.class, () ->
                AdvisorService.requireUsableExtractionModel(
                        environment(local("local-chair")), "gpt-4o", true));
    }

    @Test
    void anEmptyEnvironmentSaysWhatToDoRatherThanListingNothing() {
        AdvisorRequestException refusal = assertThrows(AdvisorRequestException.class, () ->
                AdvisorService.requireUsableExtractionModel(environment(), "local-chair", false));

        assertTrue(refusal.remediation().contains("form"),
                   "with nothing usable the answer is the form, not a shorter list: "
                   + refusal.remediation());
    }

    // ── Fixtures ────────────────────────────────────────────────────────

    private AdvisorEnvironment environment(CandidateModel... extractionModels) {
        return new AdvisorEnvironment(List.of("llama3.1:8b"),
                                      Map.of("ollama", ClientAvailability.LIVE),
                                      List.of(), List.of(extractionModels), null, Instant.EPOCH);
    }

    private CandidateModel local(String id) {
        return model(id, "ollama", "llama3.1:8b");
    }

    private CandidateModel cloud(String id) {
        return model(id, "openai", "gpt-4o");
    }

    private CandidateModel model(String id, String provider, String providerModelId) {
        return new CandidateModel(id, provider, providerModelId, "family", false, ModelRole.MEMBER,
                                  CouncilRole.PROPOSER, 0, ClientAvailability.LIVE,
                                  CandidateModel.Source.BUILT_IN);
    }
}
