package com.debopam.llmcouncil.config;

import com.debopam.llmcouncil.domain.DepthMode;
import com.debopam.llmcouncil.model.ModelRole;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CouncilConfigurationValidatorTest {

    private final CouncilConfigurationValidator validator = new CouncilConfigurationValidator(4096);

    @Test
    void acceptsMinimalValidConfiguration() {
        assertDoesNotThrow(() -> validator.validate(validProps()));
    }

    @Test
    void rejectsTestOnlyModelInRealProfile() {
        CouncilProperties props = validProps();
        props.getModels().getFirst().setTestOnly(true);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> validator.validate(props));

        assertTrue(ex.getMessage().contains("test-only model"));
    }

    @Test
    void rejectsQuorumGreaterThanMemberCount() {
        CouncilProperties props = validProps();
        props.getPolicies().get("policy").setMinimumSuccessfulDrafts(2);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> validator.validate(props));

        assertTrue(ex.getMessage().contains("minimumSuccessfulDrafts exceeds member model count"));
    }

    @Test
    void rejectsMissingDepthPolicy() {
        CouncilProperties props = validProps();
        props.getProfiles().get("local").getDepthPolicies().remove(DepthMode.RIGOROUS.name());

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> validator.validate(props));

        assertTrue(ex.getMessage().contains("missing depth policy"));
    }

    @Test
    void rejectsUnknownChairModel() {
        CouncilProperties props = validProps();
        props.getPolicies().get("policy").setChairModelId("missing-chair");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> validator.validate(props));

        assertTrue(ex.getMessage().contains("unknown chair model"));
    }

    private CouncilProperties validProps() {
        CouncilProperties props = new CouncilProperties();
        props.setModels(List.of(model("member", ModelRole.MEMBER), model("chair", ModelRole.CHAIR)));

        CouncilProperties.ProtocolProps protocol = new CouncilProperties.ProtocolProps();
        protocol.setOrderedStages(List.of("GENERATE", "SYNTHESIZE"));
        props.setProtocols(Map.of("quick", protocol));

        CouncilProperties.PolicyProps policy = new CouncilProperties.PolicyProps();
        policy.setProtocolId("quick");
        policy.setMemberModelIds(List.of("member"));
        policy.setChairModelId("chair");
        policy.setMinimumSuccessfulDrafts(1);
        props.setPolicies(Map.of("policy", policy));

        CouncilProperties.ProfileProps profile = new CouncilProperties.ProfileProps();
        profile.setTestOnly(false);
        profile.setDefaultDepth(DepthMode.QUICK);
        Map<String, String> depthPolicies = new LinkedHashMap<>();
        depthPolicies.put(DepthMode.QUICK.name(), "policy");
        depthPolicies.put(DepthMode.BALANCED.name(), "policy");
        depthPolicies.put(DepthMode.RIGOROUS.name(), "policy");
        profile.setDepthPolicies(depthPolicies);
        props.setProfiles(Map.of("local", profile));

        return props;
    }

    private CouncilProperties.ModelProps model(String id, ModelRole role) {
        CouncilProperties.ModelProps model = new CouncilProperties.ModelProps();
        model.setId(id);
        model.setProvider("mock");
        model.setProviderModelId(id);
        model.setRole(role);
        model.setDefaultOutputTokens(100);
        model.setTimeoutSeconds(10);
        return model;
    }
}
