package com.debopam.llmcouncil.advisor;

import com.debopam.llmcouncil.model.ClientAvailability;
import com.debopam.llmcouncil.model.CouncilRole;
import com.debopam.llmcouncil.model.ModelRole;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What this machine can actually run, as a value.
 *
 * <p>This record exists so that {@link ConfigSynthesizer} can be a pure
 * function. Discovering installed Ollama tags is a network call, and
 * {@code mvn test} is hermetic — so the installed list is an <b>input</b> to
 * synthesis, produced once by {@code AdvisorEnvironmentService}, never fetched
 * by the code that reasons about it. Tests build environments by hand and touch
 * no network.
 *
 * <p>The candidate models come from the <b>built-in</b> catalog. That is not an
 * oversight: user-defined models are supplied separately, alongside the rest of
 * the overlay being carried forward, because whether they survive is a property
 * of the document being written rather than of the machine.
 *
 * @param installedOllamaTags   provider model ids present in the local Ollama
 *                              runtime; empty when Ollama is unreachable
 * @param providerAvailability  provider key (lowercase) to whether it can be
 *                              called, for models not in the built-in catalog
 * @param catalogModels         every built-in model, with the availability of
 *                              the client that was built for it
 * @param extractionModels      models that may be asked to read a description;
 *                              see {@link #extractionModels()} for why this is a
 *                              different list from {@link #catalogModels()}
 * @param defaultExtractionModelId the local model to pre-select for requirement
 *                              extraction, or null when none qualifies
 * @param probedAt              when this snapshot was taken
 */
public record AdvisorEnvironment(
        List<String> installedOllamaTags,
        Map<String, ClientAvailability> providerAvailability,
        List<CandidateModel> catalogModels,
        List<CandidateModel> extractionModels,
        String defaultExtractionModelId,
        Instant probedAt
) {

    /** The provider whose models run on the user's own machine. */
    public static final String LOCAL_PROVIDER = "ollama";

    /** Defensive copies, so a probe result cannot be edited after the fact. */
    public AdvisorEnvironment {
        installedOllamaTags = installedOllamaTags == null ? List.of() : List.copyOf(installedOllamaTags);
        providerAvailability = providerAvailability == null ? Map.of() : Map.copyOf(providerAvailability);
        catalogModels = catalogModels == null ? List.of() : List.copyOf(catalogModels);
        extractionModels = extractionModels == null ? List.of() : List.copyOf(extractionModels);
        probedAt = probedAt == null ? Instant.now() : probedAt;
    }

    /**
     * The models that may be asked to read a description.
     *
     * <p>Deliberately a different list from {@link #catalogModels()}, because it
     * answers a different question. Synthesis candidates come from the shipped
     * catalog, since a model that exists only because today's overlay defines it
     * may not exist after the overlay is replaced. Extraction happens <em>now</em>,
     * against the configuration that is running, so a model the user added by
     * hand is as usable for it as a shipped one.
     *
     * <p>This is also the allowlist. The picker is built from it and the server
     * validates a submitted id against it, so a description can never redirect
     * extraction at a model the user was never shown.
     *
     * @return callable models, mock and unavailable ones excluded
     */
    public List<CandidateModel> extractionModels() {
        return extractionModels;
    }

    /**
     * Find a model a caller asked to extract with.
     *
     * @param modelId the id submitted; matched exactly, never interpreted
     * @return the model, or empty when it is not one that may be used
     */
    public java.util.Optional<CandidateModel> extractionModel(String modelId) {
        return extractionModels.stream()
                               .filter(candidate -> candidate.id().equals(modelId))
                               .findFirst();
    }

    /**
     * Whether a provider model id is installed in the local Ollama runtime.
     *
     * <p>Ollama's {@code :latest} suffix is implicit on the command line and
     * explicit in {@code /api/tags}, so {@code llama3.1} and
     * {@code llama3.1:latest} name one model. Comparing the raw strings would
     * tell a user who ran {@code ollama pull llama3.1} that they have nothing
     * installed.
     *
     * @param providerModelId the tag to look for
     * @return {@code true} when the runtime reports it
     */
    public boolean isInstalled(String providerModelId) {
        if (providerModelId == null || providerModelId.isBlank()) {
            return false;
        }
        String wanted = withImplicitLatest(providerModelId);
        return installedOllamaTags.stream()
                                  .anyMatch(tag -> withImplicitLatest(tag).equals(wanted));
    }

    /**
     * Whether a provider can be called at all.
     *
     * @param provider the provider key, in any case
     * @return the recorded availability, or {@link ClientAvailability#UNAVAILABLE}
     *         when the provider is not referenced by any model
     */
    public ClientAvailability availabilityOf(String provider) {
        if (provider == null) {
            return ClientAvailability.UNAVAILABLE;
        }
        return providerAvailability.getOrDefault(provider.toLowerCase(Locale.ROOT),
                                                 ClientAvailability.UNAVAILABLE);
    }

    /** @return {@code true} when the local runtime reported at least one model */
    public boolean hasLocalModels() {
        return !installedOllamaTags.isEmpty();
    }

    /**
     * @return providers that are live and are not the local runtime, sorted
     */
    public List<String> liveCloudProviders() {
        return providerAvailability.entrySet().stream()
                .filter(entry -> entry.getValue() == ClientAvailability.LIVE)
                .map(Map.Entry::getKey)
                .filter(provider -> !LOCAL_PROVIDER.equals(provider))
                .sorted()
                .toList();
    }

    private static String withImplicitLatest(String tag) {
        String trimmed = tag.trim();
        return trimmed.contains(":") ? trimmed : trimmed + ":latest";
    }

    /**
     * A model the synthesizer may seat, whatever defined it.
     *
     * <p>Built-in models, models the user already defined in their overlay, and
     * installed Ollama tags with no binding at all reduce to this one shape, so
     * selection does not have three code paths that could disagree about
     * diversity or role preference.
     *
     * @param id                  the id to reference, or the id to give a model
     *                            this configuration will define
     * @param provider            provider key, lowercase
     * @param providerModelId     the model's name at the provider
     * @param modelFamily         normalised family tag, used for diversity and
     *                            validator independence
     * @param familyInferred      whether the family was guessed from the model's
     *                            name rather than declared; an inferred family is
     *                            a trust signal that has to be reported
     * @param role                structural role, MEMBER / CHAIR / VALIDATOR
     * @param councilRole         debate persona
     * @param contextWindowTokens context window, or zero when unknown
     * @param availability        whether this model can be called
     * @param source              where this candidate came from
     */
    public record CandidateModel(
            String id,
            String provider,
            String providerModelId,
            String modelFamily,
            boolean familyInferred,
            ModelRole role,
            CouncilRole councilRole,
            int contextWindowTokens,
            ClientAvailability availability,
            Source source
    ) {

        /**
         * The underlying model, as {@code provider:providerModelId}.
         *
         * <p>Two ids sharing a binding are one set of weights wearing two names.
         * Seating both would draft twice, review each other, and skew the scores
         * while looking like two independent opinions — so selection deduplicates
         * on this rather than on the id.
         *
         * <p>Local tags are normalised, because {@code llama3.1} and
         * {@code llama3.1:latest} are the same weights and an unnormalised
         * comparison would seat both.
         *
         * @return a key identifying the underlying model
         */
        public String binding() {
            return provider + ":" + (local() ? withImplicitLatest(providerModelId) : providerModelId);
        }

        /** @return {@code true} when this model runs on the user's own machine */
        public boolean local() {
            return LOCAL_PROVIDER.equals(provider);
        }

        /** Where a candidate came from, which decides whether it is referenced or defined. */
        public enum Source {
            /** Shipped in {@code application.yml}; referenced by id, never redefined. */
            BUILT_IN,

            /** Already defined in the user's overlay; carried forward unchanged. */
            USER,

            /** An installed Ollama tag nothing binds yet; this configuration defines it. */
            NEW
        }
    }
}
