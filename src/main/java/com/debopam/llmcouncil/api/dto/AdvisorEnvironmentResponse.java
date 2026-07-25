package com.debopam.llmcouncil.api.dto;

import com.debopam.llmcouncil.advisor.AdvisorEnvironment;
import com.debopam.llmcouncil.model.ClientAvailability;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What this machine can run, as the setup wizard needs to show it.
 *
 * <p>Structurally incapable of carrying a credential, the same way the catalog
 * projection is: availability is a three-valued enum derived from which client
 * was built, and there is no field anywhere here for a key. A wizard that has to
 * tell somebody a provider is not configured needs the variable's <em>name</em>,
 * never its value.
 *
 * @param installedLocalModels   models present in the local runtime
 * @param providers              every provider referenced by configuration
 * @param extractionModels       models that may be asked to read a description
 * @param defaultExtractionModelId which of them to pre-select, or null when no
 *                               local model qualifies; a cloud model is never
 *                               pre-selected, because that would turn the
 *                               acknowledgement into a click-through
 * @param remediation            what to do when there is not enough to work with
 * @param probedAt               when this was measured
 */
public record AdvisorEnvironmentResponse(
        List<String> installedLocalModels,
        List<ProviderState> providers,
        List<ExtractionModel> extractionModels,
        String defaultExtractionModelId,
        List<String> remediation,
        String probedAt
) {

    /**
     * Project an environment for the wizard.
     *
     * @param environment the probed environment
     * @return the response
     */
    public static AdvisorEnvironmentResponse from(AdvisorEnvironment environment) {
        List<ProviderState> providers = new ArrayList<>();
        environment.providerAvailability().entrySet().stream()
                   .sorted(Map.Entry.comparingByKey())
                   .forEach(entry -> providers.add(new ProviderState(
                           entry.getKey(),
                           entry.getValue(),
                           AdvisorEnvironment.LOCAL_PROVIDER.equals(entry.getKey()))));

        List<ExtractionModel> extraction = environment.extractionModels().stream()
                .map(model -> new ExtractionModel(model.id(), model.provider(),
                                                  model.providerModelId(), model.local()))
                .toList();

        return new AdvisorEnvironmentResponse(
                environment.installedOllamaTags(), providers, extraction,
                environment.defaultExtractionModelId(), remediation(environment),
                environment.probedAt().toString());
    }

    /**
     * What a user should do when this machine cannot seat a council.
     *
     * <p>Empty when there is nothing to fix. Advice offered to somebody whose
     * setup is already fine reads as a warning, and a warning that never clears
     * is one people learn to ignore.
     */
    private static List<String> remediation(AdvisorEnvironment environment) {
        List<String> steps = new ArrayList<>();
        if (!environment.hasLocalModels()) {
            steps.add("No local models are installed. Start Ollama if it is not running, then "
                      + "pull at least two models from different families, for example: "
                      + "ollama pull llama3.1:8b && ollama pull mistral:7b");
        } else if (environment.installedOllamaTags().size() < 2) {
            steps.add("Only one local model is installed, so a council would have nobody to "
                      + "disagree with. Pull a second from another family, for example: "
                      + "ollama pull mistral:7b");
        }
        if (environment.liveCloudProviders().isEmpty() && !environment.hasLocalModels()) {
            steps.add("No cloud provider is active either. Providers are activated by setting "
                      + "their credential in the environment; "
                      + "GET /api/council/catalog?include=providers names the variable each one "
                      + "needs. This application never reads credentials from configuration.");
        }
        return steps;
    }

    /**
     * Whether a provider can be called.
     *
     * @param provider     the provider key
     * @param availability whether a real client was built for it
     * @param local        whether its models run on this machine
     */
    public record ProviderState(String provider, ClientAvailability availability, boolean local) {}

    /**
     * A model that may be asked to read a description.
     *
     * @param id              the id to submit; the server accepts only these
     * @param provider        the provider it would reach
     * @param providerModelId the model's name at that provider
     * @param local           whether asking it keeps the description on this machine
     */
    public record ExtractionModel(String id, String provider, String providerModelId,
                                  boolean local) {}
}
