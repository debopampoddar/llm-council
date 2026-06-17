package com.debopam.llmcouncil.application;

import com.debopam.llmcouncil.domain.DepthMode;
import com.debopam.llmcouncil.model.CouncilPolicy;
import com.debopam.llmcouncil.model.CouncilProfile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CouncilPolicyResolverTest {

    @Test
    void resolvesPolicyFromProfileAndDepth() {
        CouncilProfile profile = new CouncilProfile(
                "local", "Local", false, DepthMode.BALANCED,
                Map.of(DepthMode.QUICK, "local-quick", DepthMode.BALANCED, "local-balanced"));
        CouncilPolicy quick = policy("local-quick", "quick");
        CouncilPolicy balanced = policy("local-balanced", "balanced");

        CouncilPolicyResolver resolver = new CouncilPolicyResolver(
                Map.of("local", profile),
                Map.of("local-quick", quick, "local-balanced", balanced));

        CouncilPolicyResolver.ResolvedCouncilPolicy resolved = resolver.resolve("local", DepthMode.QUICK);

        assertEquals("local", resolved.profile().id());
        assertEquals("local-quick", resolved.policy().id());
        assertEquals("quick", resolved.policy().protocolId());
    }

    @Test
    void usesProfileDefaultDepthWhenRequestDepthIsMissing() {
        CouncilProfile profile = new CouncilProfile(
                "local", "Local", false, DepthMode.BALANCED,
                Map.of(DepthMode.BALANCED, "local-balanced"));
        CouncilPolicy balanced = policy("local-balanced", "balanced");

        CouncilPolicyResolver resolver = new CouncilPolicyResolver(
                Map.of("local", profile),
                Map.of("local-balanced", balanced));

        CouncilPolicyResolver.ResolvedCouncilPolicy resolved = resolver.resolve("local", null);

        assertEquals(DepthMode.BALANCED, resolved.depthMode());
        assertEquals("balanced", resolved.policy().protocolId());
    }

    @Test
    void rejectsUnknownProfile() {
        CouncilPolicyResolver resolver = new CouncilPolicyResolver(Map.of(), Map.of());

        assertThrows(java.util.NoSuchElementException.class,
                     () -> resolver.resolve("missing", DepthMode.BALANCED));
    }

    private CouncilPolicy policy(String id, String protocolId) {
        return new CouncilPolicy(id, protocolId, List.of("member"), "chair", "validator",
                                 1, 0, false, true);
    }
}
