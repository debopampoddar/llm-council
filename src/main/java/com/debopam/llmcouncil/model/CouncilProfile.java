package com.debopam.llmcouncil.model;

import com.debopam.llmcouncil.domain.DepthMode;

import java.util.Map;

/**
 * User-selectable council profile.
 *
 * <p>A profile does not directly expose a protocol to callers. Instead it maps
 * each depth mode to an application-owned {@link CouncilPolicy}. This keeps
 * protocol selection configuration-driven and prevents public API callers from
 * bypassing validation or budget rules.
 */
public record CouncilProfile(
        String id,
        String displayName,
        boolean testOnly,
        DepthMode defaultDepthMode,
        Map<DepthMode, String> depthPolicyIds
) {
    public String policyIdFor(DepthMode requestedDepth) {
        DepthMode effectiveDepth = requestedDepth != null ? requestedDepth : defaultDepthMode;
        String policyId = depthPolicyIds.get(effectiveDepth);
        if (policyId == null) {
            throw new IllegalArgumentException(
                    "Profile '" + id + "' does not define a policy for depth " + effectiveDepth);
        }
        return policyId;
    }
}
