package com.debopam.llmcouncil.orchestration;

import java.util.List;

/**
 * Structured review of one anonymized draft written by a council reviewer.
 * Produced by the {@link StageType#REVIEW} stage.
 *
 * @param reviewerId     Model ID of the reviewer.
 * @param draftId        ID of the draft being reviewed.
 * @param strengths      Strong parts of the draft.
 * @param issues         Problems, omissions, or factual risks.
 * @param criteria       Per-criterion scores and rationales.
 * @param overallScore   Reviewer total score, 0-100.
 * @param confidence     Reviewer confidence, 0.0-1.0.
 * @param rawText        Raw model output for debugging/artifacts.
 */
public record ReviewArtifact(
        String reviewerId,
        String draftId,
        List<String> strengths,
        List<String> issues,
        List<CriterionScore> criteria,
        int overallScore,
        double confidence,
        String rawText
) {}
