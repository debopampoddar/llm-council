package com.debopam.llmcouncil.api.dto;

import com.debopam.llmcouncil.domain.CouncilSession;
import com.debopam.llmcouncil.orchestration.CouncilContext;
import com.debopam.llmcouncil.orchestration.ScoreSummary;
import com.debopam.llmcouncil.orchestration.ValidationArtifact;
import com.debopam.llmcouncil.model.ModelCallException;
import com.debopam.llmcouncil.model.ModelFailureCategory;
import com.debopam.llmcouncil.model.ModelProfile;
import com.debopam.llmcouncil.model.ValidationIndependence;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The result of one council run, as returned to API callers.
 *
 * <p>This is where a reader finds out not just what the council concluded but
 * how much to trust it. {@code sycophancyWarnings} names members that merely
 * echoed the previous speaker during debate, {@code validationIndependence}
 * says whether the Fresh Eyes check was actually independent of the chair, and
 * {@code warnings} carries anything that degraded the run — a truncated
 * synthesis prompt, an excluded model. A run that hid those would look
 * identical to one that earned its confidence.
 *
 * <p>{@code usage} answers the other question a reader has: what it cost. See
 * {@link UsageSummary} for why an unpriced model reports no cost rather than a
 * cost of zero.
 */
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
        List<String> sycophancyWarnings,
        ScoreSummary scoreSummary,
        ValidationArtifact validation,
        ValidationIndependence validationIndependence,
        String failureReason,
        String failureCategory,
        List<ModelFailureResponse> modelFailures,
        UsageSummary usage
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
                ctx.sycophancyWarnings(),
                ctx.scoreSummary().orElse(null),
                ctx.validation().orElse(null),
                validationIndependence(ctx),
                ctx.failureMessage().orElse(null),
                failureCategory(ctx),
                ctx.modelFailures().stream()
                        .map(ModelFailureResponse::from)
                        .collect(Collectors.toList()),
                // Reported even for a failed run: the calls that ran before the
                // failure were still billed.
                UsageSummary.from(ctx)
        );
    }

    /**
     * Build a result for a run that died before producing a context.
     *
     * <p>{@code CouncilRunExecutor} hands the completion callback a null context
     * when the run threw rather than failing a stage, so there is no accumulated
     * evidence to report. Storing this shape anyway keeps the contract simple
     * for readers: a finished run always has a result, and a 404 means "still
     * running or unknown session" rather than "finished, but crashed". Every
     * evidence field is empty because nothing was gathered — that is the honest
     * report, not a degraded one.
     *
     * @param session       the session as it stood when the run died
     * @param failureReason why the run died
     * @return a FAILED result carrying only what the session knows
     */
    public static CouncilRunResponse failed(CouncilSession session, String failureReason) {
        return new CouncilRunResponse(
                session.id(),
                "FAILED",
                session.profileId(),
                session.depthMode() == null ? null : session.depthMode().name(),
                session.policyId(),
                session.protocolId(),
                "",
                0, 0, 0,
                List.of(), List.of(), List.of(), List.of(),
                null, null, null,
                failureReason,
                ModelFailureCategory.UNKNOWN.name(),
                List.of(),
                // No context means no record of what ran, so there is nothing
                // honest to report here — not even zero.
                null);
    }

    /**
     * Report how independent this run's validator was from its chair.
     *
     * <p>Surfaced on every run so a reader can tell whether a validated answer
     * was actually checked by something with different blind spots. A run whose
     * chair validated its own synthesis must not look identical to one that got
     * a genuinely independent review.
     *
     * @param ctx the completed council context
     * @return the independence tier, or null when the context has no catalog binding
     */
    private static ValidationIndependence validationIndependence(CouncilContext ctx) {
        if (ctx.catalog() == null) {
            return null;
        }
        var registry = ctx.modelRegistry();
        ModelProfile chair = registry.findModel(ctx.policy().chairModelId()).orElse(null);
        ModelProfile validator = registry.findModel(ctx.policy().validatorModelId()).orElse(null);
        if (chair == null || validator == null) {
            return ValidationIndependence.NOT_APPLICABLE;
        }
        return ValidationIndependence.between(
                chair.id(), chair.modelFamily(), chair.providerModelId(),
                validator.id(), validator.modelFamily(), validator.providerModelId());
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
