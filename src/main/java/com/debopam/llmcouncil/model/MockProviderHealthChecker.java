package com.debopam.llmcouncil.model;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class MockProviderHealthChecker implements ProviderHealthChecker {
    @Override
    public boolean supports(String provider) {
        return "mock".equalsIgnoreCase(provider);
    }

    @Override
    public ProviderHealth check(ModelProfile modelProfile) {
        return ProviderHealth.available(List.of(modelProfile.providerModelId()));
    }
}
