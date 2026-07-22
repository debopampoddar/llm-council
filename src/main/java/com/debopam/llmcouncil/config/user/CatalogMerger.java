package com.debopam.llmcouncil.config.user;

import com.debopam.llmcouncil.config.ConfigIssue;
import com.debopam.llmcouncil.config.ConfigOrigin;
import com.debopam.llmcouncil.config.CouncilCatalog;
import com.debopam.llmcouncil.domain.DepthMode;
import com.debopam.llmcouncil.model.CouncilPolicy;
import com.debopam.llmcouncil.model.CouncilProfile;
import com.debopam.llmcouncil.model.CouncilRole;
import com.debopam.llmcouncil.model.ModelClient;
import com.debopam.llmcouncil.model.ModelProfile;
import com.debopam.llmcouncil.model.ModelRegistry;
import com.debopam.llmcouncil.model.ModelRole;
import com.debopam.llmcouncil.orchestration.ProtocolDefinition;
import com.debopam.llmcouncil.orchestration.ProtocolStageOptions;
import com.debopam.llmcouncil.orchestration.StageType;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Merges a validated user overlay over the built-in catalog.
 *
 * <p>Merge semantics differ by shape, deliberately:
 *
 * <ul>
 *   <li><b>Scalars</b> — a user value replaces the built-in one; an absent value
 *       keeps it. A user overriding one field of a model should not have to
 *       restate the rest.</li>
 *   <li><b>Maps</b> ({@code depthPolicies}, {@code stageOptions}) — merged per
 *       key, so changing the BALANCED policy of a profile does not silently drop
 *       its QUICK and RIGOROUS mappings.</li>
 *   <li><b>Lists</b> ({@code memberModelIds}) — replaced wholesale. There is no
 *       sensible partial merge of an ordered council roster.</li>
 * </ul>
 *
 * <p>Built-in entities are never deleted, only shadowed. Removing one would
 * break the mock profile the test suite depends on, and a user who wants a
 * profile gone can simply not select it.
 */
public class CatalogMerger {

    private final Function<ModelProfile, ModelClient> clientFactory;

    /**
     * @param clientFactory builds a client for a user-defined model; the caller
     *                      owns provider wiring and credential detection
     */
    public CatalogMerger(Function<ModelProfile, ModelClient> clientFactory) {
        this.clientFactory = clientFactory;
    }

    /**
     * Produce the catalog the application will run from.
     *
     * @param builtIn    the catalog built from {@code application.yml}
     * @param overlay    the sanitised user overlay
     * @param issues     issues accumulated so far; carried onto the catalog
     * @param generation the generation number for the resulting catalog
     * @return the merged catalog
     */
    public CouncilCatalog merge(CouncilCatalog builtIn, UserConfigDocument overlay,
                                List<ConfigIssue> issues, long generation) {
        Map<String, ConfigOrigin> origins = new LinkedHashMap<>(builtIn.origins());

        Map<String, ModelProfile> models = new LinkedHashMap<>();
        Map<String, ModelClient> clients = new LinkedHashMap<>();
        for (String id : builtIn.modelRegistry().modelIds()) {
            models.put(id, builtIn.modelRegistry().model(id));
            clients.put(id, builtIn.modelRegistry().clientForModel(id));
        }
        for (UserConfigDocument.UserModel userModel : overlay.models()) {
            ModelProfile existing = models.get(userModel.id());
            ModelProfile merged = mergeModel(existing, userModel);
            models.put(merged.id(), merged);
            clients.put(merged.id(), clientFactory.apply(merged));
            origins.put(CouncilCatalog.key("model", merged.id()),
                        existing == null ? ConfigOrigin.USER : ConfigOrigin.USER_OVERRIDE);
        }

        Map<String, ProtocolDefinition> protocols = new LinkedHashMap<>(builtIn.protocols());
        overlay.protocols().forEach((id, userProtocol) -> {
            ProtocolDefinition base = builtIn.protocols().get(userProtocol.derivedFrom());
            if (base == null) {
                return;
            }
            protocols.put(id, deriveProtocol(id, base, userProtocol));
            origins.put(CouncilCatalog.key("protocol", id), ConfigOrigin.USER);
        });

        Map<String, CouncilPolicy> policies = new LinkedHashMap<>(builtIn.policies());
        overlay.policies().forEach((id, userPolicy) -> {
            CouncilPolicy existing = builtIn.policies().get(id);
            policies.put(id, mergePolicy(id, existing, userPolicy));
            origins.put(CouncilCatalog.key("policy", id),
                        existing == null ? ConfigOrigin.USER : ConfigOrigin.USER_OVERRIDE);
        });

        Map<String, CouncilProfile> profiles = new LinkedHashMap<>(builtIn.profiles());
        overlay.profiles().forEach((id, userProfile) -> {
            CouncilProfile existing = builtIn.profiles().get(id);
            profiles.put(id, mergeProfile(id, existing, userProfile));
            origins.put(CouncilCatalog.key("profile", id),
                        existing == null ? ConfigOrigin.USER : ConfigOrigin.USER_OVERRIDE);
        });

        return new CouncilCatalog(new ModelRegistry(models, clients), profiles, policies, protocols,
                                  origins, issues, Instant.now(), generation);
    }

    private ModelProfile mergeModel(ModelProfile existing, UserConfigDocument.UserModel user) {
        return new ModelProfile(
                user.id(),
                or(user.provider(), existing == null ? null : existing.provider()),
                or(user.providerModelId(), existing == null ? null : existing.providerModelId()),
                or(user.defaultOutputTokens(), existing == null ? 2000 : existing.defaultOutputTokens()),
                or(user.temperature(), existing == null ? 0.3 : existing.temperature()),
                user.timeoutSeconds() != null
                ? Duration.ofSeconds(user.timeoutSeconds())
                : (existing == null ? Duration.ofSeconds(120) : existing.defaultTimeout()),
                enumOr(user.role(), ModelRole.class, existing == null ? ModelRole.MEMBER : existing.role()),
                enumOr(user.councilRole(), CouncilRole.class,
                       existing == null ? CouncilRole.PROPOSER : existing.councilRole()),
                or(user.modelFamily(), existing == null ? null : existing.modelFamily()),
                or(user.contextWindowTokens(), existing == null ? 0 : existing.contextWindowTokens()));
    }

    private CouncilPolicy mergePolicy(String id, CouncilPolicy existing,
                                      UserConfigDocument.UserPolicy user) {
        // memberModelIds replaces rather than merges: a partial merge of an
        // ordered roster has no meaning a user could predict.
        List<String> members = user.memberModelIds() != null && !user.memberModelIds().isEmpty()
                               ? List.copyOf(user.memberModelIds())
                               : (existing == null ? List.of() : existing.memberModelIds());
        return new CouncilPolicy(
                id,
                or(user.protocolId(), existing == null ? null : existing.protocolId()),
                members,
                or(user.chairModelId(), existing == null ? null : existing.chairModelId()),
                user.validatorModelId() != null
                ? user.validatorModelId()
                : (existing == null ? null : existing.validatorModelId()),
                or(user.minimumSuccessfulDrafts(), existing == null ? 1 : existing.minimumSuccessfulDrafts()),
                or(user.minimumReviewsPerDraft(), existing == null ? 0 : existing.minimumReviewsPerDraft()),
                or(user.validationRequired(), existing != null && existing.validationRequired()),
                or(user.allowPartial(), existing == null || existing.allowPartial()));
    }

    private CouncilProfile mergeProfile(String id, CouncilProfile existing,
                                        UserConfigDocument.UserProfile user) {
        // depthPolicies merges per key so overriding one depth keeps the others.
        Map<DepthMode, String> depthPolicies = new LinkedHashMap<>(
                existing == null ? Map.of() : existing.depthPolicyIds());
        if (user.depthPolicies() != null) {
            user.depthPolicies().forEach((depth, policyId) ->
                    depthPolicies.put(DepthMode.valueOf(depth.toUpperCase(Locale.ROOT)), policyId));
        }
        return new CouncilProfile(
                id,
                or(user.displayName(), existing == null ? id : existing.displayName()),
                // testOnly is never user-settable: it is what keeps mock models
                // out of real councils.
                existing != null && existing.testOnly(),
                user.defaultDepth() != null
                ? DepthMode.valueOf(user.defaultDepth().toUpperCase(Locale.ROOT))
                : (existing == null ? DepthMode.BALANCED : existing.defaultDepthMode()),
                depthPolicies);
    }

    private ProtocolDefinition deriveProtocol(String id, ProtocolDefinition base,
                                              UserConfigDocument.UserProtocol user) {
        // orderedStages is inherited, never supplied: stage order is the
        // deliberation design, not a preference.
        Map<StageType, ProtocolStageOptions> stageOptions = new LinkedHashMap<>();
        base.stageOptions().forEach((stage, options) ->
                stageOptions.put(stage, new ProtocolStageOptions(new LinkedHashMap<>(options.values()))));

        if (user.stageOptions() != null) {
            user.stageOptions().forEach((stageName, options) -> {
                StageType stage = StageType.valueOf(stageName);
                Map<String, Object> merged = new LinkedHashMap<>(
                        stageOptions.containsKey(stage) ? stageOptions.get(stage).values() : Map.of());
                if (options != null) {
                    merged.putAll(options);
                }
                stageOptions.put(stage, new ProtocolStageOptions(merged));
            });
        }

        String description = user.description() != null
                             ? user.description()
                             : base.description() + " (tuned copy '" + id + "')";
        return new ProtocolDefinition(id, description, base.orderedStages(), stageOptions);
    }

    private <T> T or(T value, T fallback) {
        return value != null ? value : fallback;
    }

    private <E extends Enum<E>> E enumOr(String value, Class<E> type, E fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
    }
}
