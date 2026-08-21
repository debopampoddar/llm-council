// ── SynthesisStageExecutor.java 
package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.application.EventPublisher;
import com.debopam.llmcouncil.model.ChatMessage;
import com.debopam.llmcouncil.model.ModelCallRequest;
import com.debopam.llmcouncil.model.ModelCallResult;
import com.debopam.llmcouncil.model.ModelProfile;
import com.debopam.llmcouncil.model.ModelRegistry;
import com.debopam.llmcouncil.persistence.ArtifactStore;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * SYNTHESIZE stage: the chair model integrates all drafts, reviews, scores,
 * and debate history into the final council answer.
 */
@Component
public class SynthesisStageExecutor implements StageExecutor {
    private final ModelRegistry registry;
    private final PromptBuilder promptBuilder;
    private final EventPublisher events;
    private final ArtifactStore artifactStore;

    public SynthesisStageExecutor(ModelRegistry registry, PromptBuilder promptBuilder,
                                  EventPublisher events, ArtifactStore artifactStore) {
        this.registry = registry; this.promptBuilder = promptBuilder;
        this.events = events; this.artifactStore = artifactStore;
    }

    @Override public StageType stage() { return StageType.SYNTHESIZE; }

    @Override
    public CouncilContext execute(CouncilContext ctx, ProtocolStageOptions opts) throws Exception {
        if (ctx.drafts().size() < ctx.policy().minimumSuccessfulDrafts()) {
            ctx.markFailed(stage(), new IllegalStateException(
                    "Cannot synthesize because draft quorum is not met"));
            return ctx;
        }

        String chairId = ctx.policy().chairModelId();
        ModelProfile chair = registry.model(chairId);
        boolean preserveDissent = opts.getBoolean("preserve-dissent", true);
        events.publish(ctx.session().id(), stage().name(), "SYNTHESIS_STARTED", chairId, Map.of());

        PromptBudget budget = PromptBudget.forModel(chair);
        List<ChatMessage> messages = promptBuilder.synthesisMessages(
                ctx.session().question(), ctx.session().context(), ctx.drafts(), ctx.reviews(),
                ctx.scores(), ctx.debateRounds(), preserveDissent, budget);
        PromptBudgets.record(ctx, events, stage(), chairId, budget);

        ModelCallResult result = callAndRecord(ctx, chair, messages);
        artifactStore.writeText(ctx.session().id(),
                "raw/synthesis-" + chairId + "-attempt-1.md", result.text());

        OutputAssessment assessment = assessOutput(ctx, result.text());
        if (assessment.recoveryRecommended()) {
            events.publish(ctx.session().id(), stage().name(),
                    "SYNTHESIS_OUTPUT_RECOVERY_STARTED", chairId,
                    Map.of("reason", assessment.reason()));
            List<ChatMessage> recoveryMessages = promptBuilder.synthesisRecoveryMessages(
                    ctx.session().question(), ctx.session().context(), ctx.drafts(), ctx.reviews(),
                    ctx.scores(), ctx.debateRounds(), preserveDissent, budget);
            result = callAndRecord(ctx, chair, recoveryMessages);
            artifactStore.writeText(ctx.session().id(),
                    "raw/synthesis-" + chairId + "-attempt-2.md", result.text());
            assessment = assessOutput(ctx, result.text());
        }

        if (assessment.invariantViolation()) {
            String reason = "Synthesized answer failed the user-facing output guard after recovery: "
                    + assessment.reason();
            ctx.setSynthesisResult(null);
            ctx.markDegraded(reason);
            ctx.markFailed(stage(), new IllegalStateException(reason));
            events.publish(ctx.session().id(), stage().name(),
                    "SYNTHESIS_OUTPUT_REJECTED", chairId,
                    Map.of("reason", assessment.reason()));
            return ctx;
        }
        if (assessment.recoveryRecommended()) {
            String warning = "Synthesized answer retained after bounded cleanup because only a "
                    + "non-authoritative narration quality signal remained: " + assessment.reason();
            ctx.addWarning(warning);
            events.publish(ctx.session().id(), stage().name(),
                    "SYNTHESIS_OUTPUT_QUALITY_WARNING", chairId,
                    Map.of("reason", assessment.reason()));
        }

        ctx.setSynthesisResult(result.text());
        artifactStore.writeText(ctx.session().id(), "final/answer.md", result.text());
        events.publish(ctx.session().id(), stage().name(), "SYNTHESIS_COMPLETED", chairId,
                       Map.of("chars", result.text().length()));
        return ctx;
    }

    private ModelCallResult callAndRecord(
            CouncilContext ctx, ModelProfile chair, List<ChatMessage> messages) {
        ModelCallResult result = registry.clientForModel(chair.id()).call(
                new ModelCallRequest(ctx.session().id(), stage(), chair.id(),
                        chair.providerModelId(), messages, chair.defaultOutputTokens(),
                        chair.temperature(), false, chair.defaultTimeout()));
        ctx.recordUsage(chair.id(), stage(), result.promptTokens(),
                result.completionTokens(), result.latency());
        return result;
    }

    private OutputAssessment assessOutput(CouncilContext ctx, String answer) {
        TrustBoundaryGuard.Assessment trust = TrustBoundaryGuard.assess(
                ctx.session().context(), answer);
        if (trust.violated()) {
            return new OutputAssessment(true, true, trust.reason());
        }
        UserFacingAnswerGuard.Assessment userFacing = UserFacingAnswerGuard.assess(
                ctx.session().question(), answer);
        if (userFacing.invariantViolation()) {
            return new OutputAssessment(true, true, userFacing.reason());
        }
        return userFacing.qualityFindings().isEmpty()
                ? OutputAssessment.clear()
                : new OutputAssessment(false, true, userFacing.reason());
    }

    private record OutputAssessment(boolean invariantViolation,
                                    boolean recoveryRecommended,
                                    String reason) {
        static OutputAssessment clear() {
            return new OutputAssessment(false, false, null);
        }
    }
}
