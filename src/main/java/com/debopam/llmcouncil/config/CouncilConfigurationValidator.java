package com.debopam.llmcouncil.config;

import com.debopam.llmcouncil.domain.DepthMode;
import com.debopam.llmcouncil.model.ModelRole;
import com.debopam.llmcouncil.model.ValidationIndependence;
import com.debopam.llmcouncil.model.ValidationIndependenceClassifier;
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

            // Warn when the council is smaller, or less independent, than its
            // roster makes it look.
            warnCouncilComposition(policyId, policy, modelsById,
                                   props.getProtocols().get(policy.getProtocolId()));
        });
    }

    /**
     * Warn (do not fail) when a council's composition undercuts what it reports.
     *
     * <p>Three distinct ways a roster overstates itself:
     *
     * <ol>
     *   <li><b>The same model seated twice.</b> Two member ids resolving to one
     *       {@code providerModelId} are one set of weights sampled twice, not two
     *       opinions. Deliberate resampling is legitimate, so this stays quiet
     *       when the two entries differ in temperature — that is the difference
     *       between an accident and a choice.</li>
     *   <li><b>The chair sitting as a member.</b> The chair then synthesises a
     *       pool containing its own draft. Anonymisation cannot help: a model
     *       recognises its own writing, and self-preference is the specific bias
     *       the anonymised review stage exists to remove.</li>
     *   <li><b>A scoring strategy that cannot run.</b> {@code median} and
     *       {@code trimmed-mean} need three reviews per draft to do anything a
     *       plain average would not. Self-review is excluded, so reviews per
     *       draft is members minus one, and below three the strategy silently
     *       degrades to averaging while the configuration still names it.</li>
     * </ol>
     *
     * <p>All three are warnings. A one-model machine must still be able to run a
     * council; what it must not do is present that council as something wider.
     *
     * @param policyId   the policy being validated
     * @param policy     the policy configuration
     * @param modelsById all configured models, keyed by id
     * @param protocol   the protocol this policy runs, used for its stage options
     */
    private void warnCouncilComposition(String policyId,
                                        CouncilProperties.PolicyProps policy,
                                        Map<String, CouncilProperties.ModelProps> modelsById,
                                        CouncilProperties.ProtocolProps protocol) {
        List<CouncilProperties.ModelProps> members = policy.getMemberModelIds().stream()
                                                          .map(modelsById::get)
                                                          .filter(Objects::nonNull)
                                                          .toList();

        // (1) Two member ids, one underlying model.
        for (int i = 0; i < members.size(); i++) {
            for (int j = i + 1; j < members.size(); j++) {
                CouncilProperties.ModelProps left = members.get(i);
                CouncilProperties.ModelProps right = members.get(j);
                if (!resolvesToSameModel(left, right)) {
                    continue;
                }
                if (left.getTemperature() != right.getTemperature()) {
                    continue; // deliberate resampling of one model at two temperatures
                }
                log.warn("Policy {} seats '{}' and '{}' as separate members, but both resolve to "
                         + "provider model '{}' at the same temperature. That is one model sampled "
                         + "twice, not two opinions: it inflates the apparent council size, and its "
                         + "two drafts will review and score each other. Use a different model for "
                         + "one of them, or vary their temperature if the resampling is deliberate.",
                         policyId, left.getId(), right.getId(), left.getProviderModelId());
            }
        }

        // (2) The chair is also a member, including under a second logical id.
        CouncilProperties.ModelProps chair = modelsById.get(policy.getChairModelId());
        CouncilProperties.ModelProps overlappingMember = members.stream()
                .filter(member -> member.getId().equals(policy.getChairModelId())
                        || (chair != null && resolvesToSameModel(member, chair)))
                .findFirst()
                .orElse(null);
        if (overlappingMember != null) {
            log.warn("Policy {} seats its chair '{}' on the same underlying model as member '{}'. "
                     + "The chair will synthesise a pool containing work from its own weights, which "
                     + "anonymisation cannot correct for. Use a distinct chair model unless the "
                     + "self-preference is understood and accepted.",
                     policyId, policy.getChairModelId(), overlappingMember.getId());
        }

        // (3) A scoring strategy with nothing to work on.
        String strategy = scoringStrategy(protocol);
        int reviewsPerDraft = Math.max(0, policy.getMemberModelIds().size() - 1);
        if (reviewsPerDraft < 3 && ("median".equals(strategy) || "trimmed-mean".equals(strategy))) {
            log.warn("Policy {} selects the '{}' scoring strategy, but its {} members produce only "
                     + "{} review(s) per draft once self-review is excluded. The strategy needs "
                     + "three to behave differently from a plain average and will silently fall "
                     + "back to one. Seat more members or select 'average'.",
                     policyId, strategy, policy.getMemberModelIds().size(), reviewsPerDraft);
        }
    }

    /**
     * Whether two model entries are the same underlying model.
     *
     * <p>Compares the resolved {@code providerModelId}, the same basis
     * {@link ValidationIndependence} uses for chair and validator, so the two
     * checks cannot disagree about what "the same model" means.
     *
     * @param left  one model entry
     * @param right another model entry
     * @return {@code true} when both name the same provider model on the same provider
     */
    private boolean resolvesToSameModel(CouncilProperties.ModelProps left,
                                        CouncilProperties.ModelProps right) {
        String leftModel = left.getProviderModelId();
        String rightModel = right.getProviderModelId();
        return hasText(leftModel) && hasText(rightModel)
               && leftModel.equalsIgnoreCase(rightModel)
               && Objects.equals(left.getProvider(), right.getProvider());
    }

    /**
     * The scoring strategy a protocol's SCORE stage will use.
     *
     * @param protocol the protocol, may be null
     * @return the configured strategy, or the shipped default when unset
     */
    private String scoringStrategy(CouncilProperties.ProtocolProps protocol) {
        if (protocol == null) {
            return "confidence-weighted";
        }
        Map<String, Object> scoreOptions = protocol.getStageOptions().get("SCORE");
        if (scoreOptions == null || scoreOptions.get("scoring-strategy") == null) {
            return "confidence-weighted";
        }
        return String.valueOf(scoreOptions.get("scoring-strategy"));
    }

    /**
     * Warn (do not fail) when a validator overlaps any answer producer.
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
        log.warn("Policy {} validator '{}' shares a model family or resolved provider model with its "
                 + "chair '{}' or a council member, so validation errors are likely to be correlated. "
                 + "Prefer a validator from a family not used by any answer producer.",
                 policyId, policy.getValidatorModelId(), policy.getChairModelId());
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
     * Classify how independent a policy's validator is from all answer producers.
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
        return ValidationIndependenceClassifier.classify(
                identity(chair),
                policy.getMemberModelIds().stream()
                        .map(modelsById::get)
                        .filter(Objects::nonNull)
                        .map(CouncilConfigurationValidator::identity)
                        .toList(),
                identity(validator));
    }

    private static ValidationIndependenceClassifier.Identity identity(
            CouncilProperties.ModelProps model) {
        return new ValidationIndependenceClassifier.Identity(
                model.getId(), model.getModelFamily(), model.getProviderModelId());
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
