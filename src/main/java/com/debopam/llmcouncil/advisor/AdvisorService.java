package com.debopam.llmcouncil.advisor;

import com.debopam.llmcouncil.api.dto.CatalogDiffResponse;
import com.debopam.llmcouncil.api.dto.ConfigSaveResponse;
import com.debopam.llmcouncil.api.dto.ValidationReportResponse;
import com.debopam.llmcouncil.application.ConfigDraftService;
import com.debopam.llmcouncil.application.ConfigWriteService;
import com.debopam.llmcouncil.config.CouncilCatalogHolder;
import com.debopam.llmcouncil.config.user.UserConfigDocument;
import com.debopam.llmcouncil.model.ModelRegistry;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The advisor, as a set of calls rather than as a set of endpoints.
 *
 * <p>Everything the wizard does goes through here, and nothing here knows about
 * HTTP. That is deliberate: the command-line setup runner described in the plan
 * is not built yet, and the way to keep it cheap to build later is to make sure
 * the calls it will need already exist and already have a caller and tests. A
 * stub class that does nothing would have been worse than its absence — nobody
 * can later tell half-finished from broken.
 *
 * <p>Applying a configuration goes through the same {@link ConfigWriteService}
 * the manual path uses. There is one write path, one atomic rename, one backup,
 * and one set of rules about what may be saved.
 */
@Service
public class AdvisorService {

    private final AdvisorEnvironmentService environmentService;
    private final ConfigSynthesizer synthesizer;
    private final RequirementExtractor extractor;
    private final ConfigDraftService draftService;
    private final ConfigWriteService writeService;
    private final CouncilCatalogHolder catalogHolder;
    private final ProposalStore proposalStore;

    /**
     * @param environmentService probes what this machine can run
     * @param synthesizer        turns a requirement into configuration
     * @param extractor          reads free text into a requirement
     * @param draftService       reads, validates, and previews configuration
     * @param writeService       saves configuration
     * @param catalogHolder      resolves the model an extraction was asked for
     * @param proposalStore      keeps a council somebody saved without applying
     */
    public AdvisorService(AdvisorEnvironmentService environmentService,
                          ConfigSynthesizer synthesizer,
                          RequirementExtractor extractor,
                          ConfigDraftService draftService,
                          ConfigWriteService writeService,
                          CouncilCatalogHolder catalogHolder,
                          ProposalStore proposalStore) {
        this.environmentService = environmentService;
        this.synthesizer = synthesizer;
        this.extractor = extractor;
        this.draftService = draftService;
        this.writeService = writeService;
        this.catalogHolder = catalogHolder;
        this.proposalStore = proposalStore;
    }

    /**
     * Read a description into a requirement, using a model the caller named.
     *
     * <p>Three refusals live here rather than in the wizard, because a gate in a
     * web page is not a gate:
     *
     * <ul>
     *   <li>The model id must be one the environment offers. It is matched
     *       exactly against that list and never interpreted, so no amount of
     *       free text can redirect extraction at something else.</li>
     *   <li>A model that cannot be called, or a mock one, is refused rather than
     *       producing a fabricated requirement.</li>
     *   <li>A non-local model needs an explicit acknowledgement. Somebody
     *       describing a council they want kept local must not discover that
     *       preference by having the description sent to a cloud provider
     *       first.</li>
     * </ul>
     *
     * @param freeText                the user's description; data throughout
     * @param modelId                 the model to ask
     * @param acknowledgeCloudProvider whether the caller has confirmed sending the
     *                                description to a non-local provider
     * @return what was understood, or an outcome directing the caller to the form
     * @throws AdvisorRequestException when the model may not be used for this
     */
    public ExtractionOutcome extract(String freeText, String modelId,
                                     boolean acknowledgeCloudProvider) {
        AdvisorEnvironment.CandidateModel candidate =
                requireUsableExtractionModel(environment(), modelId, acknowledgeCloudProvider);

        ModelRegistry registry = catalogHolder.get().modelRegistry();
        return extractor.extract(freeText, registry.model(candidate.id()),
                                 registry.clientForModel(candidate.id()));
    }

    /**
     * Decide whether a model may be asked to read a description.
     *
     * <p>Separate and static so it can be tested against environments that do not
     * exist on the machine running the tests — an active cloud provider, most of
     * all, which no hermetic test can otherwise produce. The refusals are the
     * part worth proving; the call afterwards is covered by the extractor's own
     * tests.
     *
     * @param environment              what this machine can run
     * @param modelId                  the id submitted
     * @param acknowledgeCloudProvider whether sending the description off the
     *                                 machine has been confirmed
     * @return the model to use
     * @throws AdvisorRequestException when it may not be used
     */
    static AdvisorEnvironment.CandidateModel requireUsableExtractionModel(
            AdvisorEnvironment environment, String modelId, boolean acknowledgeCloudProvider) {

        AdvisorEnvironment.CandidateModel candidate = environment.extractionModel(modelId)
                .orElseThrow(() -> new AdvisorRequestException(
                        "'" + modelId + "' is not a model this installation can use to read a "
                        + "description.",
                        environment.extractionModels().isEmpty()
                        ? "No model is currently usable. Start Ollama and pull a model, or "
                          + "activate a provider; fill in the form in the meantime."
                        : "Choose one of: " + environment.extractionModels().stream()
                                .map(AdvisorEnvironment.CandidateModel::id).toList() + "."));

        if (!candidate.local() && !acknowledgeCloudProvider) {
            throw new AdvisorRequestException(
                    "Reading your description with '" + candidate.id() + "' would send it to the "
                    + candidate.provider() + " provider, which has not been acknowledged.",
                    "Confirm that you are willing to send the description to " + candidate.provider()
                    + ", or choose a model that runs on this machine.");
        }
        return candidate;
    }

    /**
     * Describe what this machine can run, probing the local runtime.
     *
     * @return the current environment
     */
    public AdvisorEnvironment environment() {
        return environmentService.describe();
    }

    /**
     * Describe the machine using an installed list already obtained.
     *
     * @param installedOllamaTags the local runtime's models
     * @return the environment, with the given installed list
     */
    public AdvisorEnvironment environment(List<String> installedOllamaTags) {
        return environmentService.describe(installedOllamaTags);
    }

    /**
     * Synthesise a council for a requirement, extending the current overlay.
     *
     * <p>The document returned always contains everything the user already had.
     * The advisor adds; it does not replace a configuration it did not write.
     *
     * @param requirement   what the user asked for
     * @param shadowDefault whether to point the {@code default} profile at the
     *                      synthesised council as well
     * @return the configuration to save, the rationale, and anything worth knowing
     */
    public SynthesisResult synthesize(CouncilRequirement requirement, boolean shadowDefault) {
        return synthesize(requirement, environment(), shadowDefault);
    }

    /**
     * Synthesise against an environment the caller already has.
     *
     * <p>Saves a second probe for a wizard that has just shown the user the
     * installed list, and keeps the environment shown and the environment used
     * from disagreeing between two requests.
     *
     * @param requirement   what the user asked for
     * @param environment   what this machine can run
     * @param shadowDefault whether to shadow the {@code default} profile too
     * @return the configuration to save, the rationale, and anything worth knowing
     */
    public SynthesisResult synthesize(CouncilRequirement requirement,
                                      AdvisorEnvironment environment,
                                      boolean shadowDefault) {
        return synthesizer.synthesize(requirement, environment, draftService.draft(), shadowDefault);
    }

    // ── Proposals ───────────────────────────────────────────────────────

    /**
     * Synthesise a council and save it without applying it.
     *
     * <p>Takes a requirement rather than a document, deliberately. There is
     * therefore no path by which a hand-assembled configuration enters the
     * proposal store: intent goes in, and the configuration that comes out is
     * the one this application derived. Nothing is applied — the catalog is
     * pinned at boot and this does not touch the overlay.
     *
     * @param requirement   what the user asked for
     * @param shadowDefault whether the council should also become the default
     * @return the saved proposal, re-checked as though it had just been read
     * @throws AdvisorRequestException when there is no council to save
     */
    public StoredProposal saveProposal(CouncilRequirement requirement, boolean shadowDefault) {
        return saveProposal(requirement, environment(), shadowDefault);
    }

    /**
     * Save a proposal against an environment the caller already has.
     *
     * <p>Same reason as {@link #synthesize(CouncilRequirement, AdvisorEnvironment, boolean)}: a
     * wizard that has just shown the user their installed models should not
     * probe again, and what it saves should be what it showed.
     *
     * @param requirement   what the user asked for
     * @param environment   what this machine can run
     * @param shadowDefault whether the council should also become the default
     * @return the saved proposal, re-checked as though it had just been read
     * @throws AdvisorRequestException when there is no council to save
     */
    public StoredProposal saveProposal(CouncilRequirement requirement,
                                       AdvisorEnvironment environment,
                                       boolean shadowDefault) {
        SynthesisResult result = synthesize(requirement, environment, shadowDefault);
        if (!result.successful()) {
            throw new AdvisorRequestException(
                    result.issues().stream()
                          .filter(issue -> issue.severity()
                                  == com.debopam.llmcouncil.config.ConfigIssue.Severity.ERROR)
                          .map(com.debopam.llmcouncil.config.ConfigIssue::message)
                          .findFirst()
                          .orElse("No council could be synthesised for this requirement."),
                    result.issues().stream()
                          .map(com.debopam.llmcouncil.config.ConfigIssue::remediation)
                          .filter(java.util.Objects::nonNull)
                          .findFirst()
                          .orElse(null));
        }

        ValidationReportResponse validation = validate(result.document());
        if (!validation.valid()) {
            // Should not happen — the end-to-end sweep exists to keep it from
            // happening — but saving something known to be unusable would turn a
            // bug here into a broken council later.
            throw new AdvisorRequestException(
                    "The synthesised configuration did not validate, so it was not saved: "
                    + validation.issues(),
                    "Report this: a synthesised configuration should always validate.");
        }

        String location = proposalStore.save(
                ProposalEnvelope.of(requirement, result, Instant.now()));
        return proposal(location, environment);
    }

    /**
     * Read the saved proposal, re-checking it against the machine as it is now.
     *
     * @return the proposal, or an absent one when nothing is saved
     */
    public StoredProposal proposal() {
        return proposal(proposalStore.location(), environment());
    }

    /**
     * Read the saved proposal, checking it against an environment already probed.
     *
     * <p>The environment is a parameter because staleness is a claim <em>about</em>
     * it. A wizard showing the user their installed models and, next to that,
     * "running the advisor again would pick differently" must have derived both
     * from the same probe, or the two halves of the screen disagree.
     *
     * @param environment what this machine can run
     * @return the proposal, or an absent one when nothing is saved
     */
    public StoredProposal proposal(AdvisorEnvironment environment) {
        return proposal(proposalStore.location(), environment);
    }

    /**
     * Discard the saved proposal.
     *
     * @return {@code true} when something was removed
     */
    public boolean discardProposal() {
        return proposalStore.discard();
    }

    private StoredProposal proposal(String location, AdvisorEnvironment environment) {
        ProposalEnvelope envelope = proposalStore.load().orElse(null);
        if (envelope == null) {
            return StoredProposal.absent(location);
        }

        String difference = resynthesisDifference(envelope, environment);
        return new StoredProposal(
                true, envelope.savedAt(), location, envelope.requirement(), envelope.document(),
                envelope.rationale(), validate(envelope.document()), preview(envelope.document()),
                difference != null, difference);
    }

    /**
     * Describe how re-running the advisor today would differ from what was saved.
     *
     * <p>Reported rather than acted on. Re-synthesising silently would change
     * what the user approved; ignoring the difference would let a proposal built
     * before a model was pulled look like the best this machine can do.
     *
     * @param envelope    the saved proposal
     * @param environment what this machine can run
     * @return what would change, or null when nothing would
     */
    private String resynthesisDifference(ProposalEnvelope envelope, AdvisorEnvironment environment) {
        if (envelope.requirement() == null) {
            return null;
        }
        SynthesisResult fresh;
        try {
            fresh = synthesize(envelope.requirement(), environment, false);
        } catch (RuntimeException ex) {
            // Re-synthesis is advisory. Failing it must not stop a user reading
            // the proposal they already have.
            return null;
        }
        if (!fresh.successful()) {
            // The strongest staleness signal there is, and one validation cannot
            // give: a proposal naming built-in model ids still resolves after the
            // models behind them have been deleted, because validation checks the
            // catalog rather than the runtime.
            return "This machine currently cannot seat any council — the models this proposal "
                   + "needs may no longer be installed, or the local runtime may not be running. "
                   + "Applying it would produce a council that cannot run.";
        }
        Set<String> saved = advisorEntityIds(envelope.document());
        Set<String> now = advisorEntityIds(fresh.document());
        if (saved.equals(now)) {
            return null;
        }
        Set<String> added = new LinkedHashSet<>(now);
        added.removeAll(saved);
        Set<String> gone = new LinkedHashSet<>(saved);
        gone.removeAll(now);
        return "Running the advisor again on this machine would produce a different council"
               + (added.isEmpty() ? "" : "; it would now use " + added)
               + (gone.isEmpty() ? "" : "; it would no longer use " + gone)
               + ". Applying this proposal applies what you approved, not what it would produce "
               + "today.";
    }

    /** The advisor-owned entities of a document, as {@code type:id} keys. */
    private Set<String> advisorEntityIds(UserConfigDocument document) {
        Set<String> ids = new LinkedHashSet<>();
        document.models().stream()
                .map(UserConfigDocument.UserModel::id)
                .filter(AdvisorIds::owns)
                .forEach(id -> ids.add("model:" + id));
        document.policies().forEach((id, policy) -> {
            if (AdvisorIds.owns(id)) {
                // The members matter as much as the id: the same policy id
                // seating different models is exactly the drift being reported.
                ids.add("policy:" + id + "=" + policy.memberModelIds() + "/"
                        + policy.chairModelId() + "/" + policy.validatorModelId());
            }
        });
        return ids;
    }

    /**
     * Check a configuration without saving it.
     *
     * @param document the configuration to check
     * @return every problem found, and whether it could be saved
     */
    public ValidationReportResponse validate(UserConfigDocument document) {
        return draftService.validate(document);
    }

    /**
     * Show what a configuration would change.
     *
     * @param document the configuration to preview
     * @return the entity diff and the resulting profile list
     */
    public CatalogDiffResponse preview(UserConfigDocument document) {
        return draftService.preview(document);
    }

    /**
     * Save a configuration, if it is clean.
     *
     * @param document the configuration to save
     * @return what happened, including why nothing was written when nothing was
     */
    public ConfigSaveResponse apply(UserConfigDocument document) {
        return writeService.save(document);
    }
}
