package com.debopam.llmcouncil.application;

import com.debopam.llmcouncil.config.CouncilCatalog;
import com.debopam.llmcouncil.config.CouncilCatalogHolder;
import com.debopam.llmcouncil.domain.DepthMode;
import com.debopam.llmcouncil.model.CouncilPolicy;
import com.debopam.llmcouncil.model.CouncilProfile;
import org.springframework.stereotype.Component;

import java.util.NoSuchElementException;

/**
 * Resolves the business policy for a request from profile plus depth mode.
 *
 * <p>This is the enforcement point for the decision that public callers do not
 * choose protocol IDs. They choose "local balanced" or "OCI rigorous" style
 * inputs, and configuration maps that to the allowed protocol and quorum.
 */
@Component
public class CouncilPolicyResolver {

    private final CouncilCatalogHolder catalogHolder;

    /**
     * @param catalogHolder holder for the active configuration snapshot
     */
    public CouncilPolicyResolver(CouncilCatalogHolder catalogHolder) {
        this.catalogHolder = catalogHolder;
    }

    /**
     * Resolve a profile and depth mode to the policy that will govern the run.
     *
     * <p>The catalog is read exactly once, so the profile and the policy it
     * names always come from the same configuration snapshot even if
     * configuration is reloaded concurrently. The snapshot is returned alongside
     * the result so the caller can carry it for the whole run.
     *
     * @param profileId requested profile id; blank or null resolves to {@code default}
     * @param depthMode requested depth; null falls back to the profile's default depth
     * @return the resolved profile, policy, effective depth mode, and catalog snapshot
     * @throws NoSuchElementException if the profile or its mapped policy is unknown
     */
    public ResolvedCouncilPolicy resolve(String profileId, DepthMode depthMode) {
        CouncilCatalog catalog = catalogHolder.get();

        String effectiveProfileId = profileId == null || profileId.isBlank() ? "default" : profileId;
        CouncilProfile profile = catalog.profiles().get(effectiveProfileId);
        if (profile == null) {
            throw new NoSuchElementException(
                    "Profile not found: " + effectiveProfileId + ". Known: " + catalog.profiles().keySet());
        }

        DepthMode effectiveDepth = depthMode != null ? depthMode : profile.defaultDepthMode();
        String policyId = profile.policyIdFor(effectiveDepth);
        CouncilPolicy policy = catalog.policies().get(policyId);
        if (policy == null) {
            throw new NoSuchElementException(
                    "Policy not found: " + policyId + ". Known: " + catalog.policies().keySet());
        }
        return new ResolvedCouncilPolicy(profile, policy, effectiveDepth, catalog);
    }

    /**
     * A profile, its resolved policy, the effective depth, and the catalog
     * snapshot they were resolved from.
     *
     * @param profile   the resolved profile
     * @param policy    the policy governing the run
     * @param depthMode the depth mode actually applied
     * @param catalog   the configuration snapshot these were resolved from; the
     *                  run keeps using this snapshot for its whole duration
     */
    public record ResolvedCouncilPolicy(CouncilProfile profile,
                                        CouncilPolicy policy,
                                        DepthMode depthMode,
                                        CouncilCatalog catalog) {}
}
