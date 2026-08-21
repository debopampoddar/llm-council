// ── ValidateStageExecutor.java 
package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.application.EventPublisher;
import com.debopam.llmcouncil.model.ChatMessage;
import com.debopam.llmcouncil.model.ModelCallException;
import com.debopam.llmcouncil.model.ModelCallRequest;
import com.debopam.llmcouncil.model.ModelCallResult;
import com.debopam.llmcouncil.model.ModelFailureCategory;
import com.debopam.llmcouncil.model.ModelProfile;
import com.debopam.llmcouncil.model.ModelRegistry;
import com.debopam.llmcouncil.persistence.ArtifactStore;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

/**
 * VALIDATE stage: performs structured Fresh Eyes checks on the synthesis and
 * enforces deterministic trust-boundary findings that a model cannot waive.
 */
@Component
public class ValidateStageExecutor implements StageExecutor {
    private static final int MAX_RECOVERY_OUTPUT_TOKENS = 4_096;

    private final ModelRegistry registry;
    private final PromptBuilder promptBuilder;
    private final StructuredOutputParser parser;
    private final EventPublisher events;
    private final ArtifactStore artifactStore;

    public ValidateStageExecutor(ModelRegistry registry, PromptBuilder promptBuilder,
                                 StructuredOutputParser parser, EventPublisher events,
                                 ArtifactStore artifactStore) {
        this.registry = registry;
        this.promptBuilder = promptBuilder;
        this.parser = parser;
        this.events = events;
        this.artifactStore = artifactStore;
    }

    @Override public StageType stage() { return StageType.VALIDATE; }

    @Override
    public CouncilContext execute(CouncilContext ctx, ProtocolStageOptions opts) throws Exception {
        if (ctx.synthesisResult().isEmpty() || ctx.synthesisResult().get().isBlank()) {
            ctx.markFailed(stage(), new IllegalStateException("No synthesis result to validate"));
            return ctx;
        }

        String validatorId = ctx.policy().validatorModelId();
        if (validatorId == null || validatorId.isBlank()) {
            if (ctx.policy().validationRequired()) {
                ctx.markFailed(stage(), new IllegalStateException("Validation is required but no validator is configured"));
            } else {
                events.publish(ctx.session().id(), stage().name(), "VALIDATION_SKIPPED", null,
                               Map.of("reason", "no validator configured"));
            }
            return ctx;
        }

        ModelProfile validator = registry.model(validatorId);
        events.publish(ctx.session().id(), stage().name(), "VALIDATION_STARTED", validatorId, Map.of());
        List<ChatMessage> messages =
                promptBuilder.validationMessages(ctx.session().question(),
                                                 ctx.session().context(),
                                                 ctx.synthesisResult().get());
        ModelCallRequest request = validationRequest(ctx, validator, messages,
                                                     validator.defaultOutputTokens());
        ModelCallResult result = callAndRecord(ctx, validator, request);
        artifactStore.writeText(ctx.session().id(),
                "raw/validation-" + validatorId + "-attempt-1.json", result.text());

        StructuredOutputParser.ValidationEnvelope parsed;
        boolean recoveryUsed = false;
        try {
            parsed = parser.parseValidation(result.text());
        } catch (IllegalArgumentException firstFailure) {
            if (!atOutputLimit(request, result)) {
                artifactStore.writeText(ctx.session().id(),
                        "raw/validation-" + validatorId + ".json", result.text());
                throw firstFailure;
            }

            if (request.maxOutputTokens() >= MAX_RECOVERY_OUTPUT_TOKENS) {
                artifactStore.writeText(ctx.session().id(),
                        "raw/validation-" + validatorId + ".json", result.text());
                throw new ModelCallException(
                        ModelFailureCategory.INVALID_MODEL_OUTPUT,
                        validator.provider(), validator.providerModelId(),
                        "Validator exhausted the maximum structured-output allowance without returning parseable JSON",
                        firstFailure);
            }

            long requestedRecovery = Math.max((long) request.maxOutputTokens() + 512L,
                                              (long) request.maxOutputTokens() * 2L);
            int recoveryTokens = (int) Math.min(MAX_RECOVERY_OUTPUT_TOKENS, requestedRecovery);
            events.publish(ctx.session().id(), stage().name(),
                    "VALIDATION_OUTPUT_RECOVERY_STARTED", validatorId,
                    Map.of("initialOutputTokens", request.maxOutputTokens(),
                           "recoveryOutputTokens", recoveryTokens));
            ModelCallRequest recoveryRequest = validationRequest(ctx, validator, messages, recoveryTokens);
            result = callAndRecord(ctx, validator, recoveryRequest);
            recoveryUsed = true;
            artifactStore.writeText(ctx.session().id(),
                    "raw/validation-" + validatorId + "-attempt-2.json", result.text());
            try {
                parsed = parser.parseValidation(result.text());
            } catch (IllegalArgumentException recoveryFailure) {
                throw invalidValidatorOutput(validator,
                        "Validator recovery did not return parseable JSON", recoveryFailure);
            }
        }

        TrustBoundaryGuard.Assessment validatorInfluence = assessValidatorRecommendations(
                ctx.session().context(), parsed);
        if (validatorInfluence.influenced()) {
            if (recoveryUsed) {
                artifactStore.writeText(ctx.session().id(),
                        "raw/validation-" + validatorId + ".json", result.text());
                throw invalidValidatorOutput(validator,
                        "Validator adopted instruction-like supporting context after its bounded recovery",
                        null);
            }
            events.publish(ctx.session().id(), stage().name(),
                    "VALIDATION_TRUST_RECOVERY_STARTED", validatorId,
                    Map.of("reason", validatorInfluence.reason(),
                           "matchedTerms", validatorInfluence.matchedTerms()));
            List<ChatMessage> recoveryMessages = promptBuilder.validationRecoveryMessages(
                    ctx.session().question(), ctx.session().context(), ctx.synthesisResult().get());
            ModelCallRequest recoveryRequest = validationRequest(
                    ctx, validator, recoveryMessages, request.maxOutputTokens());
            result = callAndRecord(ctx, validator, recoveryRequest);
            artifactStore.writeText(ctx.session().id(),
                    "raw/validation-" + validatorId + "-attempt-2.json", result.text());
            try {
                parsed = parser.parseValidation(result.text());
            } catch (IllegalArgumentException recoveryFailure) {
                throw invalidValidatorOutput(validator,
                        "Validator trust recovery did not return parseable JSON", recoveryFailure);
            }
            validatorInfluence = assessValidatorRecommendations(ctx.session().context(), parsed);
            if (validatorInfluence.influenced()) {
                artifactStore.writeText(ctx.session().id(),
                        "raw/validation-" + validatorId + ".json", result.text());
                throw invalidValidatorOutput(validator,
                        "Validator repeatedly adopted instruction-like supporting context", null);
            }
        }
        artifactStore.writeText(ctx.session().id(),
                "raw/validation-" + validatorId + ".json", result.text());
        ValidationArtifact artifact = ValidationEvidence.normalize(validatorId, parsed, result.text());
        artifact = ValidationEvidence.enforceTrustBoundary(
                artifact,
                TrustBoundaryGuard.assess(ctx.session().context(), ctx.synthesisResult().get()));
        ctx.setValidation(artifact);
        artifactStore.writeJson(ctx.session().id(), "final/validation.json", artifact);

        boolean valid = artifact.approved();
        if (!valid && ctx.policy().validationRequired()) {
            String reason = artifact.requiresHumanReview()
                    ? "Model validation could not establish material correctness; human review is required"
                    : "Fresh Eyes validation rejected the answer";
            ctx.markFailed(stage(), new IllegalStateException(reason));
        }
        events.publish(ctx.session().id(), stage().name(),
                       valid ? "VALIDATION_PASSED" : "VALIDATION_FAILED",
                       validatorId, Map.of("valid", valid, "confidence", artifact.confidence()));
        return ctx;
    }

    private ModelCallRequest validationRequest(
            CouncilContext ctx, ModelProfile validator,
            List<ChatMessage> messages,
            int outputTokens) {
        return new ModelCallRequest(ctx.session().id(), stage(), validator.id(),
                validator.providerModelId(), messages, outputTokens,
                validator.temperature(), true, validator.defaultTimeout());
    }

    private ModelCallResult callAndRecord(
            CouncilContext ctx, ModelProfile validator, ModelCallRequest request) {
        ModelCallResult result = registry.clientForModel(validator.id()).call(request);
        ctx.recordUsage(validator.id(), stage(), result.promptTokens(),
                        result.completionTokens(), result.latency());
        return result;
    }

    private boolean atOutputLimit(ModelCallRequest request, ModelCallResult result) {
        return result.completionTokens() != null
                && result.completionTokens() >= request.maxOutputTokens();
    }

    private TrustBoundaryGuard.Assessment assessValidatorRecommendations(
            String context, StructuredOutputParser.ValidationEnvelope parsed) {
        if (parsed.recommendedFixes() == null) {
            return TrustBoundaryGuard.Assessment.clear();
        }
        for (String recommendation : parsed.recommendedFixes()) {
            TrustBoundaryGuard.Assessment assessment =
                    TrustBoundaryGuard.assess(context, recommendation);
            if (assessment.influenced()) {
                return assessment;
            }
        }
        return TrustBoundaryGuard.Assessment.clear();
    }

    private ModelCallException invalidValidatorOutput(
            ModelProfile validator, String message, Throwable cause) {
        return cause == null
                ? new ModelCallException(ModelFailureCategory.INVALID_MODEL_OUTPUT,
                        validator.provider(), validator.providerModelId(), message)
                : new ModelCallException(ModelFailureCategory.INVALID_MODEL_OUTPUT,
                        validator.provider(), validator.providerModelId(), message, cause);
    }
}
