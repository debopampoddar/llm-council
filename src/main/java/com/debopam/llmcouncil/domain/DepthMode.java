package com.debopam.llmcouncil.domain;

public enum DepthMode {
    QUICK,
    BALANCED,
    THOROUGH,
    RIGOROUS,
    EXHAUSTIVE;

    public static DepthMode parse(String value) {
        if (value == null || value.isBlank()) {
            return BALANCED;
        }
        return DepthMode.valueOf(value.trim().toUpperCase());
    }
}
