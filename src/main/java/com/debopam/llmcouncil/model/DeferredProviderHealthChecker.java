package com.debopam.llmcouncil.model;

import org.springframework.stereotype.Component;

/**
 * Conservative health checker for providers whose real health depends on
 * runtime credentials or external gateways.
 *
 * <p>It keeps profile health useful for local checks without falsely failing
 * OCI/OpenAI-compatible profiles at startup or preflight time.
 */
@Component
public class DeferredProviderHealthChecker implements ProviderHealthChecker {
    @Override
    public boolean supports(String provider) {
        return "openai".equalsIgnoreCase(provider)
               || "anthropic".equalsIgnoreCase(provider)
               || "oci".equalsIgnoreCase(provider)
               || "oci-openai".equalsIgnoreCase(provider)
               || "openai-compatible".equalsIgnoreCase(provider);
    }

    @Override
    public ProviderHealth check(ModelProfile modelProfile) {
        return ProviderHealth.notChecked(
                "Provider health check is deferred to runtime credentials and endpoint configuration",
                modelProfile.providerModelId());
    }
}
