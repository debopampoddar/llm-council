package com.debopam.llmcouncil.model;

public record ModelHealth(String providerId, boolean available, String reason) {
    public static ModelHealth available(String providerId) {
        return new ModelHealth(providerId, true, null);
    }

    public static ModelHealth unavailable(String providerId, String reason) {
        return new ModelHealth(providerId, false, reason);
    }
}
