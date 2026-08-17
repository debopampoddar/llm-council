package com.debopam.llmcouncil.application;

/** Raised when a caller attempts an invalid council-session lifecycle transition. */
public class CouncilRunStateException extends IllegalStateException {
    public CouncilRunStateException(String message) {
        super(message);
    }
}
