package com.debopam.llmcouncil.api.dto;

import com.debopam.llmcouncil.orchestration.CouncilContext;
import com.debopam.llmcouncil.orchestration.ScoreSummary;
import com.debopam.llmcouncil.orchestration.ValidationArtifact;
import com.debopam.llmcouncil.model.ModelCallException;
import com.debopam.llmcouncil.model.ModelFailureCategory;

import java.util.List;
import java.util.stream.Collectors;

public record CouncilRunResponse(
        String sessionId,
        String status,
        String profileId,
        String depthMode,
        String policyId,
        String protocolId,
        String answer,
        int draftCount,
        int reviewCount,
        int debateRounds,
        List<String> participatingModels,
        List<String> excludedModels,
        List<String> warnings,
        ScoreSummary scoreSummary,
        ValidationArtifact validation,
        String failureReason,
        String failureCategory,
        List<ModelFailureResponse> modelFailures
) {
    public static CouncilRunResponse from(String sessionId, CouncilContext ctx) {
        return new CouncilRunResponse(
                sessionId,
                ctx.isTerminal() ? "FAILED" : "COMPLETED",
                ctx.profile().id(),
                ctx.session().depthMode().name(),
                ctx.policy().id(),
                ctx.protocol().id(),
                ctx.synthesisResult().orElse(""),
                ctx.drafts().size(), ctx.reviews().size(), ctx.debateRounds().size(),
                ctx.policy().memberModelIds(),
                ctx.excludedModels(),
                ctx.warnings(),
                ctx.scoreSummary().orElse(null),
                ctx.validation().orElse(null),
                ctx.failureMessage().orElse(null),
                failureCategory(ctx),
                ctx.modelFailures().stream()
                        .map(ModelFailureResponse::from)
                        .collect(Collectors.toList())
        );
    }

    private static String failureCategory(CouncilContext ctx) {
        if (!ctx.isTerminal() && ctx.failureCause() == null) {
            return null;
        }
        if (ctx.failureCause() instanceof ModelCallException modelFailure) {
            return modelFailure.category().name();
        }
        String message = ctx.failureMessage().orElse("").toLowerCase();
        if (message.contains("quorum")) {
            List<String> categories = ctx.modelFailures().stream()
                    .map(failure -> failure.category())
                    .distinct()
                    .toList();
            if (categories.size() == 1) {
                return categories.getFirst();
            }
            return ModelFailureCategory.QUORUM_NOT_MET.name();
        }
        if (message.contains("validation")) {
            return ModelFailureCategory.VALIDATION_FAILED.name();
        }
        if (message.contains("json") || message.contains("parse")) {
            return ModelFailureCategory.INVALID_MODEL_OUTPUT.name();
        }
        return ModelFailureCategory.UNKNOWN.name();
    }
}
