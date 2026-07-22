package com.debopam.llmcouncil.application;

import com.debopam.llmcouncil.api.dto.CatalogResponse;
import com.debopam.llmcouncil.config.CouncilCatalog;
import com.debopam.llmcouncil.config.CouncilCatalogHolder;
import com.debopam.llmcouncil.domain.DepthMode;
import com.debopam.llmcouncil.model.CouncilPolicy;
import com.debopam.llmcouncil.model.MockModelClient;
import com.debopam.llmcouncil.model.ModelClient;
import com.debopam.llmcouncil.model.ModelProfile;
import com.debopam.llmcouncil.model.ModelRegistry;
import com.debopam.llmcouncil.model.OllamaModelDiscoveryService;
import com.debopam.llmcouncil.model.UnavailableModelClient;
import com.debopam.llmcouncil.model.ValidationIndependence;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Builds read projections of the active {@link CouncilCatalog}.
 *
 * <p>Every projection is derived from a <b>single</b> catalog read so that all
 * requested sections describe the same configuration snapshot. Serving the
 * sections from separate reads would let a caller assemble a view whose policies
 * reference models that are absent from the same response.
 */
@Service
public class CatalogService {

    /** Sections a caller may request. */
    public static final Set<String> VALID_SECTIONS =
            new LinkedHashSet<>(List.of("profiles", "policies", "models", "protocols", "providers", "issues"));

    /** Client class names mapped to the coarse availability answer a caller needs. */
    private static final String CLIENT_LIVE = "LIVE";
    private static final String CLIENT_UNAVAILABLE = "UNAVAILABLE";
    private static final String CLIENT_MOCK = "MOCK";

    // Which environment variable activates each provider. Used only to tell a
    // user what to set — the values themselves are never read or reported here.
    private static final Map<String, String> PROVIDER_ENV_VARS = Map.of(
            "openai", "SPRING_AI_OPENAI_API_KEY",
            "openai-compatible", "SPRING_AI_OPENAI_API_KEY",
            "oci", "SPRING_AI_OPENAI_API_KEY",
            "oci-openai", "SPRING_AI_OPENAI_API_KEY",
            "anthropic", "SPRING_AI_ANTHROPIC_API_KEY",
            "gemini", "GOOGLE_CLOUD_PROJECT",
            "vertex-ai", "GOOGLE_CLOUD_PROJECT",
            "google", "GOOGLE_CLOUD_PROJECT");

    private final CouncilCatalogHolder catalogHolder;
    private final OllamaModelDiscoveryService ollamaDiscovery;

    /**
     * @param catalogHolder   holder for the active configuration snapshot
     * @param ollamaDiscovery discovery of locally installed Ollama models
     */
    public CatalogService(CouncilCatalogHolder catalogHolder,
                          OllamaModelDiscoveryService ollamaDiscovery) {
        this.catalogHolder = catalogHolder;
        this.ollamaDiscovery = ollamaDiscovery;
    }

    /**
     * Project the active catalog into the requested sections.
     *
     * @param sections        sections to include; empty or null includes all of them
     * @param includeTestOnly whether to include test-only profiles and the models
     *                        only they reference
     * @return the requested projections; sections not requested are null
     * @throws IllegalArgumentException if a requested section name is not recognised
     */
    public CatalogResponse catalog(Set<String> sections, boolean includeTestOnly) {
        Set<String> requested = (sections == null || sections.isEmpty())
                                ? VALID_SECTIONS
                                : normalise(sections);

        // Single read: every section below describes this one snapshot.
        CouncilCatalog catalog = catalogHolder.get();

        Set<String> visibleModelIds = visibleModelIds(catalog, includeTestOnly);

        return new CatalogResponse(
                catalog.generation(),
                catalog.builtAt(),
                requested.contains("profiles") ? profiles(catalog, includeTestOnly) : null,
                requested.contains("policies") ? policies(catalog) : null,
                requested.contains("models") ? models(catalog, visibleModelIds) : null,
                requested.contains("protocols") ? protocols(catalog) : null,
                requested.contains("providers") ? providers(catalog, visibleModelIds) : null,
                requested.contains("issues") ? catalog.issues() : null);
    }

    private Set<String> normalise(Set<String> sections) {
        Set<String> normalised = new LinkedHashSet<>();
        for (String section : sections) {
            String trimmed = section == null ? "" : section.trim().toLowerCase(Locale.ROOT);
            if (!VALID_SECTIONS.contains(trimmed)) {
                throw new IllegalArgumentException(
                        "Unknown catalog section '" + section + "'. Valid sections: " + VALID_SECTIONS);
            }
            normalised.add(trimmed);
        }
        return normalised;
    }

    /**
     * Determine which models a caller is allowed to see.
     *
     * <p>When test-only profiles are hidden, the models that only they reference
     * are hidden too — otherwise the mock models would appear in the catalog
     * with no visible profile explaining why they exist.
     */
    private Set<String> visibleModelIds(CouncilCatalog catalog, boolean includeTestOnly) {
        if (includeTestOnly) {
            return catalog.modelRegistry().modelIds();
        }
        Set<String> visible = new LinkedHashSet<>();
        catalog.profiles().values().stream()
               .filter(profile -> !profile.testOnly())
               .flatMap(profile -> profile.depthPolicyIds().values().stream())
               .map(policyId -> catalog.policies().get(policyId))
               .filter(java.util.Objects::nonNull)
               .forEach(policy -> visible.addAll(modelIdsOf(policy)));
        return visible;
    }

    private Set<String> modelIdsOf(CouncilPolicy policy) {
        Set<String> ids = new LinkedHashSet<>(policy.memberModelIds());
        ids.add(policy.chairModelId());
        if (policy.validatorModelId() != null && !policy.validatorModelId().isBlank()) {
            ids.add(policy.validatorModelId());
        }
        return ids;
    }

    private List<CatalogResponse.ProfileSummary> profiles(CouncilCatalog catalog, boolean includeTestOnly) {
        List<CatalogResponse.ProfileSummary> result = new ArrayList<>();
        catalog.profiles().forEach((id, profile) -> {
            if (!includeTestOnly && profile.testOnly()) {
                return;
            }
            List<DepthMode> depths = profile.depthPolicyIds().keySet().stream()
                                            .sorted(Comparator.comparing(Enum::ordinal))
                                            .toList();
            result.add(new CatalogResponse.ProfileSummary(
                    id,
                    profile.displayName(),
                    profile.defaultDepthMode(),
                    depths,
                    new LinkedHashMap<>(profile.depthPolicyIds()),
                    profile.testOnly(),
                    catalog.originOf("profile", id)));
        });
        return result;
    }

    private List<CatalogResponse.PolicySummary> policies(CouncilCatalog catalog) {
        ModelRegistry registry = catalog.modelRegistry();
        List<CatalogResponse.PolicySummary> result = new ArrayList<>();
        catalog.policies().forEach((id, policy) -> result.add(new CatalogResponse.PolicySummary(
                id,
                policy.protocolId(),
                policy.memberModelIds(),
                policy.chairModelId(),
                policy.validatorModelId(),
                policy.minimumSuccessfulDrafts(),
                policy.minimumReviewsPerDraft(),
                policy.validationRequired(),
                policy.allowPartial(),
                independenceOf(registry, policy),
                catalog.originOf("policy", id))));
        return result;
    }

    /**
     * Classify how independent a policy's validator is from its chair.
     *
     * <p>Reported on every policy so that a caller can see, before running,
     * whether a "validated" result will actually mean an independent check.
     */
    private ValidationIndependence independenceOf(ModelRegistry registry, CouncilPolicy policy) {
        ModelProfile chair = registry.findModel(policy.chairModelId()).orElse(null);
        ModelProfile validator = policy.validatorModelId() == null
                                 ? null
                                 : registry.findModel(policy.validatorModelId()).orElse(null);
        if (chair == null || validator == null) {
            return ValidationIndependence.NOT_APPLICABLE;
        }
        return ValidationIndependence.between(
                chair.id(), chair.modelFamily(), chair.providerModelId(),
                validator.id(), validator.modelFamily(), validator.providerModelId());
    }

    private List<CatalogResponse.ModelSummary> models(CouncilCatalog catalog, Set<String> visibleModelIds) {
        ModelRegistry registry = catalog.modelRegistry();
        List<CatalogResponse.ModelSummary> result = new ArrayList<>();
        for (String id : registry.modelIds()) {
            if (!visibleModelIds.contains(id)) {
                continue;
            }
            ModelProfile model = registry.model(id);
            result.add(new CatalogResponse.ModelSummary(
                    model.id(),
                    model.provider(),
                    model.providerModelId(),
                    model.defaultOutputTokens(),
                    model.temperature(),
                    model.defaultTimeout().toSeconds(),
                    model.role().name(),
                    model.councilRole() == null ? null : model.councilRole().name(),
                    model.modelFamily(),
                    clientKind(registry.clientForModel(id)),
                    catalog.originOf("model", id)));
        }
        return result;
    }

    private List<CatalogResponse.ProtocolSummary> protocols(CouncilCatalog catalog) {
        List<CatalogResponse.ProtocolSummary> result = new ArrayList<>();
        catalog.protocols().forEach((id, protocol) -> {
            Map<String, Map<String, Object>> stageOptions = new LinkedHashMap<>();
            protocol.stageOptions().forEach((stage, options) ->
                    stageOptions.put(stage.name(), new LinkedHashMap<>(options.values())));
            result.add(new CatalogResponse.ProtocolSummary(
                    id,
                    protocol.description(),
                    protocol.orderedStages().stream().map(Enum::name).toList(),
                    stageOptions,
                    catalog.originOf("protocol", id)));
        });
        return result;
    }

    /**
     * Report provider availability without ever touching credential values.
     *
     * <p>Availability is inferred from the clients that were actually built: if
     * a provider's models resolved to live clients, its credentials passed the
     * placeholder check at startup. This keeps the read path structurally unable
     * to leak a key, rather than relying on remembering not to print one.
     */
    private List<CatalogResponse.ProviderStatus> providers(CouncilCatalog catalog, Set<String> visibleModelIds) {
        ModelRegistry registry = catalog.modelRegistry();
        Map<String, Boolean> activeByProvider = new LinkedHashMap<>();
        Map<String, String> reasonByProvider = new LinkedHashMap<>();

        for (String id : new TreeSet<>(registry.modelIds())) {
            if (!visibleModelIds.contains(id)) {
                continue;
            }
            ModelProfile model = registry.model(id);
            String provider = model.provider().toLowerCase(Locale.ROOT);
            ModelClient client = registry.clientForModel(id);
            boolean live = CLIENT_LIVE.equals(clientKind(client));

            activeByProvider.merge(provider, live, (existing, added) -> existing || added);
            if (!live && client instanceof UnavailableModelClient unavailable) {
                reasonByProvider.putIfAbsent(provider, unavailable.reason());
            }
        }

        List<String> ollamaModels = activeByProvider.containsKey("ollama")
                                    ? ollamaDiscovery.installedModels()
                                    : List.of();

        List<CatalogResponse.ProviderStatus> result = new ArrayList<>();
        activeByProvider.forEach((provider, active) -> result.add(new CatalogResponse.ProviderStatus(
                provider,
                active,
                active ? null : reasonByProvider.get(provider),
                active ? null : PROVIDER_ENV_VARS.get(provider),
                "ollama".equals(provider) ? ollamaModels : List.of())));
        return result;
    }

    private String clientKind(ModelClient client) {
        if (client instanceof MockModelClient) {
            return CLIENT_MOCK;
        }
        if (client instanceof UnavailableModelClient) {
            return CLIENT_UNAVAILABLE;
        }
        return CLIENT_LIVE;
    }
}
