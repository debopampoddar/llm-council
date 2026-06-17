package com.debopam.llmcouncil.model;

public interface ProviderHealthChecker {
    boolean supports(String provider);
    ProviderHealth check(ModelProfile modelProfile);
}
