package com.debopam.llmcouncil.config;

import com.debopam.llmcouncil.model.CouncilPolicy;
import com.debopam.llmcouncil.model.CouncilProfile;
import com.debopam.llmcouncil.model.ModelRegistry;
import com.debopam.llmcouncil.orchestration.ProtocolDefinition;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Builds {@link CouncilCatalog} instances for tests.
 *
 * <p>Keeps the catalog's full constructor out of individual tests so that
 * adding a field to the catalog does not require editing every test that
 * happens to need one.
 */
public final class TestCatalogs {

    private TestCatalogs() {
    }

    /**
     * Build a catalog holder containing only profiles and policies.
     *
     * @param profiles profile id to profile
     * @param policies policy id to policy
     * @return a holder ready to read
     */
    public static CouncilCatalogHolder holder(Map<String, CouncilProfile> profiles,
                                              Map<String, CouncilPolicy> policies) {
        return holder(new ModelRegistry(Map.of(), Map.of()), profiles, policies, Map.of());
    }

    /**
     * Build a catalog holder with an explicit model registry.
     *
     * @param registry the model registry
     * @param profiles profile id to profile
     * @param policies policy id to policy
     * @return a holder ready to read
     */
    public static CouncilCatalogHolder holder(ModelRegistry registry,
                                              Map<String, CouncilProfile> profiles,
                                              Map<String, CouncilPolicy> policies) {
        return holder(registry, profiles, policies, Map.of());
    }

    /**
     * Build a catalog holder with every section supplied.
     *
     * @param registry  the model registry
     * @param profiles  profile id to profile
     * @param policies  policy id to policy
     * @param protocols protocol id to definition
     * @return a holder ready to read
     */
    public static CouncilCatalogHolder holder(ModelRegistry registry,
                                              Map<String, CouncilProfile> profiles,
                                              Map<String, CouncilPolicy> policies,
                                              Map<String, ProtocolDefinition> protocols) {
        return new CouncilCatalogHolder(
                catalog(registry, profiles, policies, protocols));
    }

    /**
     * Build a catalog with every section supplied.
     *
     * @param registry  the model registry
     * @param profiles  profile id to profile
     * @param policies  policy id to policy
     * @param protocols protocol id to definition
     * @return the catalog, at generation 1 with no issues
     */
    public static CouncilCatalog catalog(ModelRegistry registry,
                                         Map<String, CouncilProfile> profiles,
                                         Map<String, CouncilPolicy> policies,
                                         Map<String, ProtocolDefinition> protocols) {
        return new CouncilCatalog(registry, profiles, policies, protocols,
                                  Map.of(), List.of(), Instant.now(), 1L);
    }
}
