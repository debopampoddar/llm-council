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

import java.util.List;

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

    /**
     * @param environmentService probes what this machine can run
     * @param synthesizer        turns a requirement into configuration
     * @param extractor          reads free text into a requirement
     * @param draftService       reads, validates, and previews configuration
     * @param writeService       saves configuration
     * @param catalogHolder      resolves the model an extraction was asked for
     */
    public AdvisorService(AdvisorEnvironmentService environmentService,
                          ConfigSynthesizer synthesizer,
                          RequirementExtractor extractor,
                          ConfigDraftService draftService,
                          ConfigWriteService writeService,
                          CouncilCatalogHolder catalogHolder) {
        this.environmentService = environmentService;
        this.synthesizer = synthesizer;
        this.extractor = extractor;
        this.draftService = draftService;
        this.writeService = writeService;
        this.catalogHolder = catalogHolder;
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
