package com.debopam.llmcouncil.advisor;

import com.debopam.llmcouncil.api.dto.CatalogDiffResponse;
import com.debopam.llmcouncil.api.dto.ConfigSaveResponse;
import com.debopam.llmcouncil.api.dto.ValidationReportResponse;
import com.debopam.llmcouncil.application.ConfigDraftService;
import com.debopam.llmcouncil.application.ConfigWriteService;
import com.debopam.llmcouncil.config.user.UserConfigDocument;
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
    private final ConfigDraftService draftService;
    private final ConfigWriteService writeService;

    /**
     * @param environmentService probes what this machine can run
     * @param synthesizer        turns a requirement into configuration
     * @param draftService       reads, validates, and previews configuration
     * @param writeService       saves configuration
     */
    public AdvisorService(AdvisorEnvironmentService environmentService,
                          ConfigSynthesizer synthesizer,
                          ConfigDraftService draftService,
                          ConfigWriteService writeService) {
        this.environmentService = environmentService;
        this.synthesizer = synthesizer;
        this.draftService = draftService;
        this.writeService = writeService;
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
