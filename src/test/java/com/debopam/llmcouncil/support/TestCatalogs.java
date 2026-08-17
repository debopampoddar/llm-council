package com.debopam.llmcouncil.support;

import com.debopam.llmcouncil.config.CouncilCatalog;
import com.debopam.llmcouncil.config.CouncilCatalogHolder;
import com.debopam.llmcouncil.config.CouncilRuntimeSettings;
import com.debopam.llmcouncil.model.ModelRegistry;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Small fixtures shared by the regenerated suite. */
public final class TestCatalogs {
    private TestCatalogs() {}

    public static CouncilCatalogHolder holder(int maxRuns, String artifactPath) {
        CouncilCatalog catalog = new CouncilCatalog(
                new ModelRegistry(Map.of(), Map.of()),
                Map.of(), Map.of(), Map.of(), Map.of(),
                new CouncilRuntimeSettings(maxRuns, 4, artifactPath),
                List.of(), Instant.now(), 1L);
        return new CouncilCatalogHolder(catalog);
    }
}
