package com.debopam.llmcouncil.model;

import java.util.List;

public record ProviderHealth(
        boolean available,
        String status,
        String detail,
        List<String> knownProviderModels
) {
    public static ProviderHealth available(List<String> knownProviderModels) {
        return new ProviderHealth(true, "AVAILABLE", null, List.copyOf(knownProviderModels));
    }

    public static ProviderHealth notChecked(String detail, String providerModelId) {
        return new ProviderHealth(true, "NOT_CHECKED", detail, List.of(providerModelId));
    }

    public static ProviderHealth unavailable(String status, String detail) {
        return new ProviderHealth(false, status, detail, List.of());
    }
}
