package com.debopam.llmcouncil.config;

import com.debopam.llmcouncil.domain.DepthMode;
import com.debopam.llmcouncil.model.CouncilPolicy;
import com.debopam.llmcouncil.model.CouncilProfile;
import com.debopam.llmcouncil.model.ModelRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CouncilCatalogTest {

    @Test
    void copiesInputCollectionsSoLaterMutationCannotAffectTheCatalog() {
        Map<String, CouncilProfile> profiles = new HashMap<>();
        profiles.put("local", profile("local"));
        Map<String, CouncilPolicy> policies = new HashMap<>();
        policies.put("local-quick", policy("local-quick"));
        Map<String, ConfigOrigin> origins = new HashMap<>();
        origins.put(CouncilCatalog.key("profile", "local"), ConfigOrigin.BUILT_IN);
        List<ConfigIssue> issues = new ArrayList<>();

        CouncilCatalog catalog = new CouncilCatalog(emptyRegistry(), profiles, policies,
                                                   Map.of(), origins, issues, Instant.now(), 1L);

        profiles.put("sneaked-in", profile("sneaked-in"));
        policies.clear();
        origins.clear();
        issues.add(ConfigIssue.error("policy:x", "added after construction"));

        assertEquals(1, catalog.profiles().size());
        assertTrue(catalog.profiles().containsKey("local"));
        assertEquals(1, catalog.policies().size());
        assertEquals(ConfigOrigin.BUILT_IN, catalog.originOf("profile", "local"));
        assertTrue(catalog.issues().isEmpty());
    }

    @Test
    void returnedCollectionsAreImmutable() {
        CouncilCatalog catalog = new CouncilCatalog(emptyRegistry(), Map.of("local", profile("local")),
                                                    Map.of(), Map.of(), Map.of(), List.of(),
                                                    Instant.now(), 1L);

        assertThrows(UnsupportedOperationException.class,
                     () -> catalog.profiles().put("other", profile("other")));
    }

    @Test
    void unknownEntityDefaultsToBuiltInOrigin() {
        CouncilCatalog catalog = new CouncilCatalog(emptyRegistry(), Map.of(), Map.of(), Map.of(),
                                                    Map.of(), List.of(), Instant.now(), 1L);

        assertEquals(ConfigOrigin.BUILT_IN, catalog.originOf("model", "never-registered"));
    }

    @Test
    void holderRejectsReadBeforeInitialisation() {
        CouncilCatalogHolder holder = new CouncilCatalogHolder();

        assertFalse(holder.isInitialised());
        assertThrows(IllegalStateException.class, holder::get);
    }

    @Test
    void holderRejectsAccidentalReplacement() {
        CouncilCatalog first = new CouncilCatalog(emptyRegistry(), Map.of(), Map.of(), Map.of(),
                                                  Map.of(), List.of(), Instant.now(), 1L);
        CouncilCatalogHolder holder = new CouncilCatalogHolder();
        holder.initialise(first);

        // Replacing a live catalog is a deliberate reload operation, never a
        // second call to initialise.
        assertThrows(IllegalStateException.class, () -> holder.initialise(first));
        assertEquals(first, holder.get());
    }

    private ModelRegistry emptyRegistry() {
        return new ModelRegistry(Map.of(), Map.of());
    }

    private CouncilProfile profile(String id) {
        return new CouncilProfile(id, id, false, DepthMode.QUICK, Map.of(DepthMode.QUICK, "local-quick"));
    }

    private CouncilPolicy policy(String id) {
        return new CouncilPolicy(id, "quick", List.of("member"), "chair", null, 1, 0, false, true);
    }
}
