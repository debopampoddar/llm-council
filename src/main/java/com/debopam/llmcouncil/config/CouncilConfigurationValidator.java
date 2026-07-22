package com.debopam.llmcouncil.config;

import com.debopam.llmcouncil.domain.DepthMode;
import com.debopam.llmcouncil.model.ModelRole;
import com.debopam.llmcouncil.model.ValidationIndependence;
import com.debopam.llmcouncil.orchestration.StageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

    /** Flat allowance for synthesis instructions, scores, and scaffolding. */
    private static final int SYNTHESIS_OVERHEAD_TOKENS = 600;

    private final Integer ollamaNumCtx;

    /**
     * @param ollamaNumCtx the configured Ollama context size, used to resolve
     *                     the effective window of local models
     */
    public CouncilConfigurationValidator(
            @Value("${spring.ai.ollama.chat.options.num-ctx:4096}") Integer ollamaNumCtx) {
        this.ollamaNumCtx = ollamaNumCtx;
    }

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

            // Warn when the Fresh Eyes validator is not actually fresh.
            warnLowValidationIndependence(policyId, policy, modelsById);

            // Warn when the chair cannot physically hold the evidence it must synthesise.
            warnSynthesisWillNotFit(policyId, policy, modelsById, props.getProtocols().get(policy.getProtocolId()));
        });
    }

    /**
     * Warn (do not fail) when a policy's validator is not independent of its chair.
     *
     * <p>The validation stage exists to catch errors the chair made while
     * synthesising, which only works if the validator does not share the chair's
     * blind spots. A validator running on the same weights as the chair shares
     * all of them.
     *
     * <p>This is never a hard failure — a machine that can only run one model
     * must still be able to run a council. What it must not do is report
     * validated output without saying how independent that validation was: a
     * "validated" marker makes a reader trust an answer more, so rubber-stamped
     * validation is worse than none. Set {@code acknowledgeSelfValidation: true}
     * on the policy to silence the warning where the trade-off is deliberate;
     * the tier is still reported on the run either way.
     *
     * @param policyId   the policy being validated
     * @param policy     the policy configuration
     * @param modelsById all configured models, keyed by id
     */
    private void warnLowValidationIndependence(String policyId,
                                               CouncilProperties.PolicyProps policy,
                                               Map<String, CouncilProperties.ModelProps> modelsById) {
        ValidationIndependence tier = validationIndependence(policy, modelsById);
        if (!tier.isReduced() || policy.isAcknowledgeSelfValidation()) {
            return;
        }
        if (tier == ValidationIndependence.SELF_VALIDATION) {
            log.warn("Policy {} uses the same model ('{}') as both chair and validator. "
                     + "Fresh Eyes validation cannot be independent: the chair is validating its own "
                     + "synthesis and shares all of its own blind spots. Prefer a validator from a "
                     + "different model family, or set acknowledgeSelfValidation: true to accept this.",
                     policyId, policy.getChairModelId());
            return;
        }
        log.warn("Policy {} chair '{}' and validator '{}' share a model family or resolve to the same "
                 + "provider model, so validation errors are likely to be correlated. Prefer a validator "
                 + "from a different model family.",
                 policyId, policy.getChairModelId(), policy.getValidatorModelId());
    }

    /**
     * Warn when a policy's chair cannot hold the evidence it will be asked to
     * synthesise.
     *
     * <p>The chair's synthesis prompt carries every member draft plus reviews,
     * scores, and debate turns. When that exceeds the chair's context window the
     * prompt is truncated to fit, and the final answer is built from part of the
     * council's work. That is reported at run time too, but a user is far better
     * served by learning it at boot than by silently getting weaker answers.
     *
     * <p>Estimate only: it counts drafts at their configured output size and adds
     * allowances for reviews and debate <em>when the protocol actually runs those
     * stages</em>, so it catches clearly-broken configurations without warning
     * about protocols that never accumulate that evidence.
     *
     * @param policyId   the policy being validated
     * @param policy     the policy configuration
     * @param modelsById all configured models, keyed by id
     */
    private void warnSynthesisWillNotFit(String policyId,
                                         CouncilProperties.PolicyProps policy,
                                         Map<String, CouncilProperties.ModelProps> modelsById,
                                         CouncilProperties.ProtocolProps protocol) {
        CouncilProperties.ModelProps chair = modelsById.get(policy.getChairModelId());
        if (chair == null) {
            return;
        }
        int contextWindow = ModelContextWindows.resolve(chair, ollamaNumCtx);
        if (contextWindow <= 0) {
            return;
        }

        int draftTokens = policy.getMemberModelIds().stream()
                                .map(modelsById::get)
                                .filter(Objects::nonNull)
                                .mapToInt(CouncilProperties.ModelProps::getDefaultOutputTokens)
                                .sum();
        // Only charge for evidence the protocol actually produces. A QUICK
        // protocol runs GENERATE then SYNTHESIZE, so billing it for reviews and
        // debate would warn about a policy that fits comfortably.
        List<String> stages = protocol == null ? List.of() : protocol.getOrderedStages();
        int reviewTokens = stages.contains("REVIEW") || stages.contains("REVIEW_POST_DEBATE")
                           ? draftTokens / 2
                           : 0;
        int debateTokens = stages.contains("DEBATE") ? draftTokens : 0;
        int evidenceTokens = draftTokens + reviewTokens + debateTokens + SYNTHESIS_OVERHEAD_TOKENS;
        int usableTokens = contextWindow - chair.getDefaultOutputTokens();

        if (evidenceTokens > usableTokens) {
            log.warn("Policy {} chair '{}' has a {} token context window and reserves {} for its own output, "
                     + "leaving room for about {} tokens of prompt, but its members can produce roughly {} "
                     + "tokens of evidence. Synthesis prompts will be truncated. Reduce member "
                     + "defaultOutputTokens, use fewer members, or raise the chair's contextWindowTokens.",
                     policyId, chair.getId(), contextWindow, chair.getDefaultOutputTokens(),
                     usableTokens, evidenceTokens);
        }
    }

    /**
     * Classify how independent a policy's validator is from its chair.
     *
     * @param policy     the policy configuration
     * @param modelsById all configured models, keyed by id
     * @return the independence tier, or {@link ValidationIndependence#NOT_APPLICABLE}
     *         when the policy declares no validator
     */
    static ValidationIndependence validationIndependence(
            CouncilProperties.PolicyProps policy,
            Map<String, CouncilProperties.ModelProps> modelsById) {
        CouncilProperties.ModelProps chair = modelsById.get(policy.getChairModelId());
        CouncilProperties.ModelProps validator = modelsById.get(policy.getValidatorModelId());
        if (chair == null || validator == null) {
            return ValidationIndependence.NOT_APPLICABLE;
        }
        return ValidationIndependence.between(
                chair.getId(), chair.getModelFamily(), chair.getProviderModelId(),
                validator.getId(), validator.getModelFamily(), validator.getProviderModelId());
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
     *— Warn (do not fail) when a policy's member models all belong to
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
