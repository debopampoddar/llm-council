package com.debopam.llmcouncil.application;

/** A probe was attempted before the global cooldown elapsed. */
public class ModelProbeThrottledException extends RuntimeException {
    private final long retryAfterSeconds;

    public ModelProbeThrottledException(long retryAfterSeconds) {
        super("A model probe ran too recently.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
