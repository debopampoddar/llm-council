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
    CANCELLED,

    /**
     * The process died while this run was executing.
     *
     * <p>Distinct from {@code FAILED} because nothing went wrong with the
     * council: it was never given the chance to finish. Distinct from
     * {@code RUNNING} because it is over — a run does not survive a restart, so
     * a {@code RUNNING} session found at boot is by definition orphaned, and
     * leaving it in that status shows the user a spinner for a run that will
     * never finish.
     */
    INTERRUPTED
}
