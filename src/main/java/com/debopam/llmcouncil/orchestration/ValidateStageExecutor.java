// ── ValidateStageExecutor.java 
package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.application.EventPublisher;
import com.debopam.llmcouncil.model.ModelCallRequest;
import com.debopam.llmcouncil.model.ModelCallResult;
import com.debopam.llmcouncil.model.ModelProfile;
import com.debopam.llmcouncil.model.ModelRegistry;
import com.debopam.llmcouncil.persistence.ArtifactStore;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

/**
 * VALIDATE stage: performs sanity checks on the synthesis result.
 * Currently verifies the synthesis is non-empty; extend with self-consistency
 * or factuality checks as needed.
 */
@Component
public class ValidateStageExecutor implements StageExecutor {
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
        ModelCallResult result = registry.clientForModel(validatorId).call(
                new ModelCallRequest(ctx.session().id(), stage(), validator.id(),
                                     validator.providerModelId(),
                                     promptBuilder.validationMessages(ctx.session().question(),
                                                                      ctx.session().context(),
                                                                      ctx.synthesisResult().get()),
                                     validator.defaultOutputTokens(), validator.temperature(), true,
                                     validator.defaultTimeout()));
        ctx.recordUsage(validator.id(), stage(), result.promptTokens(), result.completionTokens(), result.latency());
        artifactStore.writeText(ctx.session().id(), "raw/validation-" + validatorId + ".json", result.text());

        StructuredOutputParser.ValidationEnvelope parsed = parser.parseValidation(result.text());
        ValidationArtifact artifact = new ValidationArtifact(
                validatorId,
                parsed.approved(),
                parsed.confidence(),
                parsed.issues() == null ? List.of() : parsed.issues(),
                parsed.recommendedFixes() == null ? List.of() : parsed.recommendedFixes(),
                parsed.criteria() == null ? Map.of() : parsed.criteria(),
                parsed.requiresHumanReview(),
                result.text());
        ctx.setValidation(artifact);
        artifactStore.writeJson(ctx.session().id(), "final/validation.json", artifact);

        boolean valid = artifact.approved();
        if (!valid && ctx.policy().validationRequired()) {
            ctx.markFailed(stage(), new IllegalStateException("Fresh Eyes validation rejected the answer"));
        }
        events.publish(ctx.session().id(), stage().name(),
                       valid ? "VALIDATION_PASSED" : "VALIDATION_FAILED",
                       validatorId, Map.of("valid", valid, "confidence", artifact.confidence()));
        return ctx;
    }
}
