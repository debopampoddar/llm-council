package com.debopam.llmcouncil.config;

import com.debopam.llmcouncil.domain.DepthMode;
import com.debopam.llmcouncil.model.ModelRole;
import com.debopam.llmcouncil.orchestration.StageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Startup validator for the configuration-driven council control plane.
 */
@Component
public class CouncilConfigurationValidator {

    private static final Logger log = LoggerFactory.getLogger(CouncilConfigurationValidator.class);

    public void validate(CouncilProperties props) {
        Map<String, CouncilProperties.ModelProps> modelsById = modelsById(props);
        validateProtocols(props);
        validatePolicies(props, modelsById);
        validateProfiles(props, modelsById);
    }

    private Map<String, CouncilProperties.ModelProps> modelsById(CouncilProperties props) {
        Map<String, CouncilProperties.ModelProps> models = new HashMap<>();
        for (CouncilProperties.ModelProps model : props.getModels()) {
            require(hasText(model.getId()), "A model is missing id");
            require(hasText(model.getProvider()), "Model " + model.getId() + " is missing provider");
            require(hasText(model.getProviderModelId()), "Model " + model.getId() + " is missing providerModelId");
            require(model.getDefaultOutputTokens() > 0,
                    "Model " + model.getId() + " must have positive defaultOutputTokens");
            require(model.getTimeoutSeconds() > 0,
                    "Model " + model.getId() + " must have positive timeoutSeconds");
            require(!models.containsKey(model.getId()), "Duplicate model id: " + model.getId());
            models.put(model.getId(), model);
        }
        return models;
    }

    private void validateProtocols(CouncilProperties props) {
        props.getProtocols().forEach((protocolId, protocol) -> {
            require(hasText(protocolId), "Protocol id must not be blank");
            require(!protocol.getOrderedStages().isEmpty(),
                    "Protocol " + protocolId + " must define orderedStages");
            for (String stage : protocol.getOrderedStages()) {
                StageType.valueOf(stage);
            }
            for (String stage : protocol.getStageOptions().keySet()) {
                StageType.valueOf(stage);
            }
        });
    }

    private void validatePolicies(CouncilProperties props,
                                  Map<String, CouncilProperties.ModelProps> modelsById) {
        props.getPolicies().forEach((policyId, policy) -> {
            require(hasText(policyId), "Policy id must not be blank");
            require(props.getProtocols().containsKey(policy.getProtocolId()),
                    "Policy " + policyId + " references unknown protocol " + policy.getProtocolId());
            require(!policy.getMemberModelIds().isEmpty(),
                    "Policy " + policyId + " must define at least one member model");
            require(policy.getMinimumSuccessfulDrafts() > 0,
                    "Policy " + policyId + " must require at least one successful draft");
            require(policy.getMinimumSuccessfulDrafts() <= policy.getMemberModelIds().size(),
                    "Policy " + policyId + " minimumSuccessfulDrafts exceeds member model count");
            require(policy.getMinimumReviewsPerDraft() >= 0,
                    "Policy " + policyId + " minimumReviewsPerDraft must not be negative");

            for (String modelId : policy.getMemberModelIds()) {
                require(modelsById.containsKey(modelId),
                        "Policy " + policyId + " references unknown member model " + modelId);
            }

            CouncilProperties.ModelProps chair = requireModel(modelsById, policy.getChairModelId(),
                                                              "Policy " + policyId + " references unknown chair model ");
            require(chair.getRole() == ModelRole.CHAIR || chair.getRole() == ModelRole.MEMBER,
                    "Policy " + policyId + " chair model " + chair.getId()
                    + " has incompatible role " + chair.getRole());

            if (hasText(policy.getValidatorModelId())) {
                CouncilProperties.ModelProps validator = requireModel(modelsById, policy.getValidatorModelId(),
                                                                      "Policy " + policyId + " references unknown validator model ");
                require(validator.getRole() == ModelRole.VALIDATOR || validator.getRole() == ModelRole.CHAIR,
                        "Policy " + policyId + " validator model " + validator.getId()
                        + " has incompatible role " + validator.getRole());
            }

            // Warn if all member models share the same architecture family.
            warnLowDiversity(policyId, policy, modelsById);
        });
    }

    private void validateProfiles(CouncilProperties props,
                                  Map<String, CouncilProperties.ModelProps> modelsById) {
        props.getProfiles().forEach((profileId, profile) -> {
            require(hasText(profileId), "Profile id must not be blank");
            for (DepthMode depthMode : DepthMode.values()) {
                require(profile.getDepthPolicies().containsKey(depthMode.name()),
                        "Profile " + profileId + " is missing depth policy for " + depthMode);
            }
            profile.getDepthPolicies().forEach((depth, policyId) -> {
                DepthMode.valueOf(depth.toUpperCase());
                CouncilProperties.PolicyProps policy = props.getPolicies().get(policyId);
                require(policy != null, "Profile " + profileId + " references unknown policy " + policyId);
                if (!profile.isTestOnly()) {
                    assertNoTestOnlyModels(profileId, policyId, policy, modelsById);
                }
            });
        });
    }

    private void assertNoTestOnlyModels(String profileId,
                                        String policyId,
                                        CouncilProperties.PolicyProps policy,
                                        Map<String, CouncilProperties.ModelProps> modelsById) {
        List<String> modelIds = new ArrayList<>(policy.getMemberModelIds());
        modelIds.add(policy.getChairModelId());
        if (hasText(policy.getValidatorModelId())) {
            modelIds.add(policy.getValidatorModelId());
        }
        for (String modelId : modelIds) {
            CouncilProperties.ModelProps model = modelsById.get(modelId);
            require(model == null || !model.isTestOnly(),
                    "Non-test profile " + profileId + " policy " + policyId
                    + " references test-only model " + modelId);
        }
    }

    private CouncilProperties.ModelProps requireModel(Map<String, CouncilProperties.ModelProps> modelsById,
                                                      String modelId,
                                                      String messagePrefix) {
        require(hasText(modelId), messagePrefix + "<blank>");
        CouncilProperties.ModelProps model = modelsById.get(modelId);
        require(model != null, messagePrefix + modelId);
        return model;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(Objects.requireNonNull(message));
        }
    }

    /**
     * Gap 1.2 — Warn (do not fail) when a policy's member models all belong to
     * the same model family. Homogeneous councils are more susceptible to shared
     * biases and correlated errors.
     */
    private void warnLowDiversity(String policyId, CouncilProperties.PolicyProps policy,
                                   Map<String, CouncilProperties.ModelProps> modelsById) {
        List<String> families = policy.getMemberModelIds().stream()
                .map(modelsById::get)
                .filter(Objects::nonNull)
                .map(CouncilProperties.ModelProps::getModelFamily)
                .filter(f -> f != null && !f.isBlank())
                .distinct()
                .toList();

        long membersWithFamily = policy.getMemberModelIds().stream()
                .map(modelsById::get)
                .filter(Objects::nonNull)
                .filter(m -> m.getModelFamily() != null && !m.getModelFamily().isBlank())
                .count();

        // Warn if all members with a modelFamily tag share the same family
        if (membersWithFamily > 1 && families.size() == 1) {
            log.warn("Policy {} has {} member models all from model family '{}'. "
                    + "Council diversity is reduced; consider adding models from different architectures.",
                    policyId, membersWithFamily, families.getFirst());
        }

        // Warn about untagged models that prevent diversity validation
        long untagged = policy.getMemberModelIds().size() - membersWithFamily;
        if (untagged > 0) {
            log.warn("Policy {} has {} member model(s) without modelFamily set. "
                    + "Set modelFamily for all models to enable diversity validation.",
                    policyId, untagged);
        }
    }
}
