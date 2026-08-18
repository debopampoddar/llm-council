package com.debopam.llmcouncil.config;

import com.debopam.llmcouncil.model.ModelClient;
import com.debopam.llmcouncil.model.ProbeModelClientFactory;
import org.springframework.stereotype.Component;

/** Routes configuration probes through the exact adapters used by council runs. */
@Component
public class ConfiguredProbeModelClientFactory implements ProbeModelClientFactory {

    private final CouncilConfig councilConfig;

    public ConfiguredProbeModelClientFactory(CouncilConfig councilConfig) {
        this.councilConfig = councilConfig;
    }

    @Override
    public ModelClient create(String provider, String providerModelId) {
        return councilConfig.buildProbeClient(provider, providerModelId);
    }
}
