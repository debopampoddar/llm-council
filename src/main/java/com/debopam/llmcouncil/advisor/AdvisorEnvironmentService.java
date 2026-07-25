package com.debopam.llmcouncil.advisor;

import com.debopam.llmcouncil.advisor.AdvisorEnvironment.CandidateModel;
import com.debopam.llmcouncil.config.CouncilCatalog;
import com.debopam.llmcouncil.config.CouncilCatalogHolder;
import com.debopam.llmcouncil.model.ClientAvailability;
import com.debopam.llmcouncil.model.CouncilPolicy;
import com.debopam.llmcouncil.model.CouncilProfile;
import com.debopam.llmcouncil.model.ModelProfile;
import com.debopam.llmcouncil.model.ModelRegistry;
import com.debopam.llmcouncil.model.OllamaModelDiscoveryService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Reports what this machine can actually run.
 *
 * <p>The <b>only</b> place in the advisor that performs I/O. Everything that
 * reasons about the answer takes an {@link AdvisorEnvironment} value instead, so
 * synthesis stays a pure function and its tests need no running Ollama.
 *
 * <p>Candidates come from {@link CouncilCatalogHolder#builtIn()} rather than the
 * active catalog, and that is load-bearing rather than incidental. The advisor
 * writes a document that the user's overlay will consist of; a model that exists
 * today only because the <em>current</em> overlay defines it is supplied
 * separately, as part of the configuration being extended, so that whether it
 * survives is decided by the document rather than assumed.
 */
@Service
public class AdvisorEnvironmentService {

    /** The built-in profile whose choice of model the extraction default follows. */
    static final String LOCAL_PROFILE_ID = "local";

    private final CouncilCatalogHolder catalogHolder;
    private final OllamaModelDiscoveryService ollamaDiscovery;

    /**
     * @param catalogHolder   holds the shipped and active configuration snapshots
     * @param ollamaDiscovery discovery of locally installed Ollama models
     */
    public AdvisorEnvironmentService(CouncilCatalogHolder catalogHolder,
                                     OllamaModelDiscoveryService ollamaDiscovery) {
        this.catalogHolder = catalogHolder;
        this.ollamaDiscovery = ollamaDiscovery;
    }

    /**
     * Probe the machine and describe what it can run.
     *
     * <p>Discovery is advisory and never throws: an unreachable Ollama produces
     * an empty installed list, which synthesis reports as an actionable error
     * rather than as an empty council.
     *
     * @return the environment as it is right now
     */
    public AdvisorEnvironment describe() {
        return describe(ollamaDiscovery.installedModels());
    }

    /**
     * Describe the machine using an installed list somebody else obtained.
     *
     * <p>Public because it is the seam that keeps discovery out of everything
     * downstream. A caller holding a recent list — a wizard that already showed
     * it to the user, a test — should not pay for a second probe, and a test that
     * needs a specific installed list must not depend on what happens to be
     * pulled on the machine running it.
     *
     * @param installedOllamaTags provider model ids present in the local runtime
     * @return the environment, with the given installed list
     */
    public AdvisorEnvironment describe(List<String> installedOllamaTags) {
        CouncilCatalog builtIn = catalogHolder.builtIn();
        ModelRegistry registry = builtIn.modelRegistry();

        List<CandidateModel> candidates = new ArrayList<>();
        Map<String, ClientAvailability> providers = new LinkedHashMap<>();

        // Sorted, because modelIds() is a copied map's key set and therefore has
        // no order of its own. Anything derived from this list — generated ids
        // most of all — would otherwise vary between runs.
        for (String id : new TreeSet<>(registry.modelIds())) {
            ModelProfile model = registry.model(id);
            ClientAvailability availability = ClientAvailability.of(registry.clientForModel(id));
            String provider = model.provider() == null ? "" : model.provider().toLowerCase(Locale.ROOT);

            candidates.add(new CandidateModel(
                    model.id(), provider, model.providerModelId(), model.modelFamily(), false,
                    model.role(), model.councilRole(), model.contextWindowTokens(),
                    availability, CandidateModel.Source.BUILT_IN));

            // A provider is live when any of its models resolved to a live
            // client. Availability is a property of the credential, not of the
            // individual binding.
            providers.merge(provider, availability,
                            (existing, added) -> existing.callable() || added.callable()
                                                 ? ClientAvailability.LIVE
                                                 : existing);
        }

        return new AdvisorEnvironment(
                installedOllamaTags, providers, candidates,
                defaultExtractionModel(builtIn, candidates, installedOllamaTags),
                Instant.now());
    }

    /**
     * Choose the model to pre-select for requirement extraction.
     *
     * <p>The local profile's chair, when it is usable. That model is the one this
     * installation has already chosen to trust for the synthesis-shaped job, and
     * mapping free text onto a small record is a synthesis-shaped job; following
     * the profile also means the extraction default moves if a user re-points it.
     *
     * <p>A cloud model is never the default even when one is live. Sending a
     * description to a third party requires an explicit acknowledgement, and a
     * pre-selected cloud model turns that into a click-through.
     *
     * @param builtIn    the shipped catalog
     * @param candidates every built-in model with its availability
     * @param installed  the local runtime's models
     * @return the model id to pre-select, or null when nothing local qualifies
     */
    private String defaultExtractionModel(CouncilCatalog builtIn, List<CandidateModel> candidates,
                                          List<String> installed) {
        Map<String, CandidateModel> byId = new LinkedHashMap<>();
        candidates.forEach(candidate -> byId.put(candidate.id(), candidate));

        for (String id : preferredExtractionIds(builtIn)) {
            CandidateModel candidate = byId.get(id);
            if (usableForExtraction(candidate, installed)) {
                return candidate.id();
            }
        }
        return candidates.stream()
                         .filter(candidate -> usableForExtraction(candidate, installed))
                         .map(CandidateModel::id)
                         .sorted()
                         .findFirst()
                         .orElse(null);
    }

    /** The local profile's chair, then its members, in the order it lists them. */
    private List<String> preferredExtractionIds(CouncilCatalog builtIn) {
        CouncilProfile profile = builtIn.profiles().get(LOCAL_PROFILE_ID);
        if (profile == null) {
            return List.of();
        }
        CouncilPolicy policy = Optional
                .ofNullable(profile.depthPolicyIds().get(profile.defaultDepthMode()))
                .map(builtIn.policies()::get)
                .orElse(null);
        if (policy == null) {
            return List.of();
        }
        List<String> preferred = new ArrayList<>();
        preferred.add(policy.chairModelId());
        preferred.addAll(policy.memberModelIds());
        return preferred;
    }

    private boolean usableForExtraction(CandidateModel candidate, List<String> installed) {
        return candidate != null
               && candidate.local()
               && candidate.availability().callable()
               && installed.stream().anyMatch(tag -> sameTag(tag, candidate.providerModelId()));
    }

    private boolean sameTag(String left, String right) {
        return withImplicitLatest(left).equals(withImplicitLatest(right));
    }

    private String withImplicitLatest(String tag) {
        String trimmed = tag == null ? "" : tag.trim();
        return trimmed.contains(":") ? trimmed : trimmed + ":latest";
    }
}
