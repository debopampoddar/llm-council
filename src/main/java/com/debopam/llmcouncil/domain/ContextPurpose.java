package com.debopam.llmcouncil.domain;

/**
 * Declares how supporting context is meant to be used by the council.
 *
 * <p>{@link #EVIDENCE} is the safe default: instruction-bearing lines are
 * removed before any model call. {@link #ANALYSIS_SUBJECT} is an explicit
 * opt-in for tasks whose purpose is to inspect or explain the supplied text
 * itself, such as analysing a quoted prompt-injection attempt.
 */
public enum ContextPurpose {
    EVIDENCE,
    ANALYSIS_SUBJECT
}
