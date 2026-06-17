package com.debopam.llmcouncil.domain;

/**
 * Top-level lifecycle state for a council session.
 *
 * <p>Detailed failure causes live on {@link CouncilSession#failureReason()} so
 * the public API can keep a small status enum while still explaining what went
 * wrong.
 */
public enum CouncilStatus {
    CREATED,
    RUNNING,
    COMPLETED,
    PARTIAL,
    FAILED,
    CANCELLED
}
