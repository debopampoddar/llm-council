package com.debopam.llmcouncil.application;

import com.debopam.llmcouncil.domain.DepthMode;
import com.debopam.llmcouncil.model.CouncilPolicy;
import com.debopam.llmcouncil.model.CouncilProfile;
import org.springframework.stereotype.Component;

import java.util.Map;
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
    private final Map<String, CouncilProfile> profiles;
    private final Map<String, CouncilPolicy> policies;

    public CouncilPolicyResolver(Map<String, CouncilProfile> councilProfiles,
                                 Map<String, CouncilPolicy> councilPolicies) {
        this.profiles = councilProfiles;
        this.policies = councilPolicies;
    }

    public ResolvedCouncilPolicy resolve(String profileId, DepthMode depthMode) {
        String effectiveProfileId = profileId == null || profileId.isBlank() ? "default" : profileId;
        CouncilProfile profile = profiles.get(effectiveProfileId);
        if (profile == null) {
            throw new NoSuchElementException(
                    "Profile not found: " + effectiveProfileId + ". Known: " + profiles.keySet());
        }

        DepthMode effectiveDepth = depthMode != null ? depthMode : profile.defaultDepthMode();
        String policyId = profile.policyIdFor(effectiveDepth);
        CouncilPolicy policy = policies.get(policyId);
        if (policy == null) {
            throw new NoSuchElementException(
                    "Policy not found: " + policyId + ". Known: " + policies.keySet());
        }
        return new ResolvedCouncilPolicy(profile, policy, effectiveDepth);
    }

    public record ResolvedCouncilPolicy(CouncilProfile profile, CouncilPolicy policy, DepthMode depthMode) {}
}
