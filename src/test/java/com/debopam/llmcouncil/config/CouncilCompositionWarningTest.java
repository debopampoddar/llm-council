package com.debopam.llmcouncil.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.debopam.llmcouncil.domain.DepthMode;
import com.debopam.llmcouncil.model.ModelRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boot warnings for councils that are smaller, or less independent, than their
 * roster suggests.
 *
 * <p>Three overstatements the shipped configuration itself contained:
 * {@code local-rigorous} listed three members of which two resolved to
 * {@code llama3.1:8b}; the same policy seated its chair as a member, so the
 * chair synthesised a pool containing its own draft; and two logical cloud
 * member ids resolved to one provider model. None of these failed validation,
 * none appeared in any report, and each made a council look wider than it was.
 *
 * <p>Every case here is paired with the configuration that must stay silent.
 * A warning that fires on everything is the same as one that fires on nothing.
 */
class CouncilCompositionWarningTest {

    private final CouncilConfigurationValidator validator = new CouncilConfigurationValidator(4096);

    private ListAppender<ILoggingEvent> appender;
    private Logger validatorLogger;

    @BeforeEach
    void captureWarnings() {
        validatorLogger = (Logger) LoggerFactory.getLogger(CouncilConfigurationValidator.class);
        appender = new ListAppender<>();
        appender.start();
        validatorLogger.addAppender(appender);
    }

    @AfterEach
    void releaseAppender() {
        validatorLogger.detachAppender(appender);
        appender.stop();
    }

    // ── The same model seated twice

    @Test
    @DisplayName("two member ids resolving to one provider model are warned about")
    void duplicateMemberIsWarnedAbout() {
        CouncilProperties props = validProps();
        props.getModels().add(aliasOf("member", "member-again"));
        props.getPolicies().get("policy").setMemberModelIds(List.of("member", "member-again"));

        validator.validate(props);

        assertTrue(warned("resolve to provider model"),
                   "one set of weights sampled twice is not two opinions: " + warnings());
    }

    @Test
    @DisplayName("distinct members are not warned about")
    void distinctMembersStaySilent() {
        // Control: the check must be able to stay quiet, or the assertion above
        // proves only that it warns unconditionally.
        CouncilProperties props = validProps();
        props.getModels().add(model("second", ModelRole.MEMBER));
        props.getPolicies().get("policy").setMemberModelIds(List.of("member", "second"));

        validator.validate(props);

        assertFalse(warned("resolve to provider model"), warnings());
    }

    @Test
    @DisplayName("the same model at two temperatures is deliberate resampling, not an accident")
    void differingTemperatureIsNotWarnedAbout() {
        CouncilProperties props = validProps();
        CouncilProperties.ModelProps warmer = aliasOf("member", "member-warm");
        warmer.setTemperature(0.9);
        props.getModels().add(warmer);
        props.getPolicies().get("policy").setMemberModelIds(List.of("member", "member-warm"));

        validator.validate(props);

        assertFalse(warned("resolve to provider model"),
                   "varying temperature is how a user says the resampling is intentional: "
                   + warnings());
    }

    // ── The chair sitting as a member

    @Test
    @DisplayName("a chair seated as a member is warned about")
    void chairAsMemberIsWarnedAbout() {
        CouncilProperties props = validProps();
        props.getPolicies().get("policy").setMemberModelIds(List.of("member", "chair"));

        validator.validate(props);

        assertTrue(warned("seats its chair"),
                   "the chair would synthesise a pool containing its own draft: " + warnings());
    }

    @Test
    @DisplayName("a member alias resolving to the chair model is warned about")
    void chairProviderModelAliasAsMemberIsWarnedAbout() {
        CouncilProperties props = validProps();
        CouncilProperties.ModelProps alias = model("chair-alias", ModelRole.MEMBER);
        alias.setProviderModelId("chair");
        props.getModels().add(alias);
        props.getPolicies().get("policy").setMemberModelIds(List.of("member", "chair-alias"));

        validator.validate(props);

        assertTrue(warned("seats its chair"),
                "logical ids must not hide that the chair also produced a draft: " + warnings());
    }

    @Test
    @DisplayName("a chair outside the roster is not warned about")
    void chairOutsideRosterStaysSilent() {
        validator.validate(validProps());

        assertFalse(warned("seats its chair"), warnings());
    }

    // ── A scoring strategy with nothing to aggregate

    @Test
    @DisplayName("median scoring on a two-member council is warned about")
    void medianOnTooFewReviewersIsWarnedAbout() {
        // Two members produce one review per draft once self-review is excluded,
        // so "median" is the median of a single value.
        CouncilProperties props = propsWithScoringStrategy("median");
        props.getModels().add(model("second", ModelRole.MEMBER));
        props.getPolicies().get("policy").setMemberModelIds(List.of("member", "second"));

        validator.validate(props);

        assertTrue(warned("scoring strategy"), warnings());
    }

    @Test
    @DisplayName("median scoring with four members is not warned about")
    void medianWithEnoughReviewersStaysSilent() {
        CouncilProperties props = propsWithScoringStrategy("median");
        props.getModels().add(model("second", ModelRole.MEMBER));
        props.getModels().add(model("third", ModelRole.MEMBER));
        props.getModels().add(model("fourth", ModelRole.MEMBER));
        props.getPolicies().get("policy")
             .setMemberModelIds(List.of("member", "second", "third", "fourth"));

        validator.validate(props);

        assertFalse(warned("scoring strategy"),
                    "three reviews per draft is enough for a median to mean something: "
                    + warnings());
    }

    @Test
    @DisplayName("the default strategy on a small council is not warned about")
    void defaultStrategyStaysSilent() {
        // confidence-weighted degrades gracefully at any size; only median and
        // trimmed-mean silently become an average.
        CouncilProperties props = validProps();
        props.getModels().add(model("second", ModelRole.MEMBER));
        props.getPolicies().get("policy").setMemberModelIds(List.of("member", "second"));

        validator.validate(props);

        assertFalse(warned("scoring strategy"), warnings());
    }

    // ── Fixtures

    private boolean warned(String fragment) {
        return appender.list.stream()
                            .filter(event -> event.getLevel() == Level.WARN)
                            .anyMatch(event -> event.getFormattedMessage().contains(fragment));
    }

    private String warnings() {
        return appender.list.stream()
                            .filter(event -> event.getLevel() == Level.WARN)
                            .map(ILoggingEvent::getFormattedMessage)
                            .toList()
                            .toString();
    }

    private CouncilProperties propsWithScoringStrategy(String strategy) {
        CouncilProperties props = validProps();
        CouncilProperties.ProtocolProps protocol = new CouncilProperties.ProtocolProps();
        protocol.setOrderedStages(List.of("GENERATE", "REVIEW", "SCORE", "SYNTHESIZE"));
        Map<String, Map<String, Object>> stageOptions = new LinkedHashMap<>();
        stageOptions.put("SCORE", Map.of("scoring-strategy", strategy));
        protocol.setStageOptions(stageOptions);
        props.setProtocols(Map.of("quick", protocol));
        return props;
    }

    private CouncilProperties validProps() {
        CouncilProperties props = new CouncilProperties();
        props.setModels(new java.util.ArrayList<>(
                List.of(model("member", ModelRole.MEMBER), model("chair", ModelRole.CHAIR))));

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

    /** A second logical model pointing at the same provider model as {@code sourceId}. */
    private CouncilProperties.ModelProps aliasOf(String sourceId, String newId) {
        CouncilProperties.ModelProps alias = model(newId, ModelRole.MEMBER);
        alias.setProviderModelId(sourceId);
        return alias;
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
