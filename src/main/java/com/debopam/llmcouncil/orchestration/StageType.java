package com.debopam.llmcouncil.orchestration;

/**
 * All protocol stages that can appear in a protocol's {@code orderedStages} list.
 *
 * <p><b>Gap 2.4 (Post-Debate Re-Review):</b> {@link #REVIEW_POST_DEBATE} runs
 * a second peer review after debate so reviewers can incorporate debate arguments.
 *
 * <p><b>Gap 4.3 (Post-Debate Draft Revision):</b> {@link #REVISE} asks each
 * member model to produce a revised draft incorporating debate insights.
 */
public enum StageType {
    GENERATE,
    ANONYMIZE,
    AGGREGATE,
    REVIEW,
    SCORE,
    DEBATE,

    /** Each member revises their draft using debate insights. */
    REVISE,

    /** Post-debate peer review that incorporates debate arguments. */
    REVIEW_POST_DEBATE,

    SYNTHESIZE,
    VALIDATE,
    EXPORT
}
