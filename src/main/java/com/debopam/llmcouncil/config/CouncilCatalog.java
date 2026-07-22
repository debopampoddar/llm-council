package com.debopam.llmcouncil.config;

import com.debopam.llmcouncil.model.CouncilPolicy;
import com.debopam.llmcouncil.model.CouncilProfile;
import com.debopam.llmcouncil.model.ModelRegistry;
import com.debopam.llmcouncil.orchestration.ProtocolDefinition;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable snapshot of everything resolved from configuration.
 *
 * <p>Before this type existed, models, profiles, policies, and protocols were
 * four independent singletons materialised at boot and injected separately —
 * two of them by raw generic type ({@code Map<String, CouncilProfile>}), which
 * cannot be swapped and cannot coexist with a second map of the same type.
 * Collapsing them into one snapshot means a reader can obtain a mutually
 * consistent view of the whole control plane in a single read, which is what
 * makes live configuration reload safe to add later.
 *
 * <p>A council run resolves exactly one catalog at run start and keeps it for
 * the duration, so a reload never changes the models or quorum out from under
 * a run already in progress.
 *
 * @param modelRegistry model profiles and their backing clients
 * @param profiles      profile id to profile
 * @param policies      policy id to policy
 * @param protocols     protocol id to definition
 * @param origins       entity key ({@code type:id}) to provenance; see {@link ConfigOrigin}
 * @param issues        problems found while building this catalog; empty when
 *                      only built-in configuration is present
 * @param builtAt       when this snapshot was constructed
 * @param generation    monotonically increasing snapshot counter, starting at 1
 */
public record CouncilCatalog(
        ModelRegistry modelRegistry,
        Map<String, CouncilProfile> profiles,
        Map<String, CouncilPolicy> policies,
        Map<String, ProtocolDefinition> protocols,
        Map<String, ConfigOrigin> origins,
        List<ConfigIssue> issues,
        Instant builtAt,
        long generation
) {

    /**
     * Defensively copies every collection so a caller retaining a reference to
     * an input map cannot mutate the catalog after construction.
     */
    public CouncilCatalog {
        Objects.requireNonNull(modelRegistry, "modelRegistry");
        profiles = Map.copyOf(profiles);
        policies = Map.copyOf(policies);
        protocols = Map.copyOf(protocols);
        origins = Map.copyOf(origins);
        issues = List.copyOf(issues);
        builtAt = builtAt == null ? Instant.now() : builtAt;
    }

    /**
     * Look up the provenance of a configuration entity.
     *
     * @param type entity type, one of {@code model}, {@code profile},
     *             {@code policy}, or {@code protocol}
     * @param id   the entity's id
     * @return the recorded origin, defaulting to {@link ConfigOrigin#BUILT_IN}
     *         when the entity predates origin tracking
     */
    public ConfigOrigin originOf(String type, String id) {
        return origins.getOrDefault(type + ":" + id, ConfigOrigin.BUILT_IN);
    }

    /**
     * Build the key used in the {@link #origins} map and in
     * {@link ConfigIssue#entityKey()}.
     *
     * @param type entity type
     * @param id   the entity's id
     * @return the composite key {@code type:id}
     */
    public static String key(String type, String id) {
        return type + ":" + id;
    }
}
