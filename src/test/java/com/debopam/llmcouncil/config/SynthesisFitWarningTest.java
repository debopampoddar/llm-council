package com.debopam.llmcouncil.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.debopam.llmcouncil.model.ModelRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the boot warning that a chair cannot hold the evidence its council
 * will produce.
 *
 * <p>Worth testing despite being log-only: the warning is the earliest signal a
 * user gets that answers will be synthesised from truncated evidence, and a
 * silently broken warning is indistinguishable from a healthy configuration.
 */
class SynthesisFitWarningTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void captureLogs() {
        logger = (Logger) LoggerFactory.getLogger(CouncilConfigurationValidator.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void warnsWhenMembersProduceMoreEvidenceThanTheChairCanHold() {
        // Three members at 1200 output tokens, plus reviews and debate, against
        // a 4096 token chair — the shipped local-rigorous shape.
        CouncilProperties props = props(4096, 1800, List.of(1200, 1200, 1200),
                                        List.of("GENERATE", "REVIEW", "DEBATE", "SYNTHESIZE"));

        new CouncilConfigurationValidator(4096).validate(props);

        assertTrue(warnings().anyMatch(message -> message.contains("Synthesis prompts will be truncated")),
                   "an over-budget council must say so at boot");
    }

    @Test
    void doesNotWarnWhenTheProtocolNeverAccumulatesThatEvidence() {
        // Same models, but a QUICK protocol: no reviews, no debate. Charging it
        // for evidence it never produces would warn about a healthy config.
        CouncilProperties props = props(4096, 1800, List.of(1200),
                                        List.of("GENERATE", "SYNTHESIZE"));

        new CouncilConfigurationValidator(4096).validate(props);

        assertFalse(warnings().anyMatch(message -> message.contains("Synthesis prompts will be truncated")));
    }

    @Test
    void warnsWhenTheChairWindowIsDerivedRatherThanConfigured() {
        // Regression guard. Local models leave contextWindowTokens unset and rely
        // on the provider default, so a validator reading the raw configured
        // value sees 0 and skips them — silently disabling the check for exactly
        // the models it exists to protect. The chair below is over budget only
        // once the window is derived from num-ctx.
        CouncilProperties props = props(0, 1800, List.of(1200, 1200, 1200),
                                        List.of("GENERATE", "REVIEW", "DEBATE", "SYNTHESIZE"));

        new CouncilConfigurationValidator(4096).validate(props);

        assertTrue(warnings().anyMatch(message -> message.contains("Synthesis prompts will be truncated")),
                   "the check must apply to models that derive their window from the provider");
    }

    @Test
    void doesNotWarnWhenTheChairHasAmpleRoom() {
        CouncilProperties props = props(200_000, 2000, List.of(1200, 1200, 1200),
                                        List.of("GENERATE", "REVIEW", "DEBATE", "SYNTHESIZE"));

        new CouncilConfigurationValidator(4096).validate(props);

        assertFalse(warnings().anyMatch(message -> message.contains("Synthesis prompts will be truncated")));
    }

    private java.util.stream.Stream<String> warnings() {
        return appender.list.stream()
                            .filter(event -> event.getLevel() == Level.WARN)
                            .map(ILoggingEvent::getFormattedMessage);
    }

    private CouncilProperties props(int chairWindow, int chairOutput,
                                    List<Integer> memberOutputs, List<String> stages) {
        CouncilProperties props = new CouncilProperties();

        CouncilProperties.ModelProps chair = model("chair", ModelRole.CHAIR, chairOutput, "chair-model");
        // Zero leaves the window unset so it is derived from the provider, which
        // is how every shipped local model is configured.
        if (chairWindow > 0) {
            chair.setContextWindowTokens(chairWindow);
        }
        props.getModels().add(chair);

        List<String> memberIds = new java.util.ArrayList<>();
        for (int i = 0; i < memberOutputs.size(); i++) {
            String id = "member-" + i;
            props.getModels().add(model(id, ModelRole.MEMBER, memberOutputs.get(i), "member-model-" + i));
            memberIds.add(id);
        }

        CouncilProperties.ProtocolProps protocol = new CouncilProperties.ProtocolProps();
        protocol.setOrderedStages(stages);
        props.getProtocols().put("test-protocol", protocol);

        CouncilProperties.PolicyProps policy = new CouncilProperties.PolicyProps();
        policy.setProtocolId("test-protocol");
        policy.setMemberModelIds(memberIds);
        policy.setChairModelId("chair");
        policy.setMinimumSuccessfulDrafts(1);
        props.getPolicies().put("test-policy", policy);

        return props;
    }

    private CouncilProperties.ModelProps model(String id, ModelRole role, int outputTokens, String providerModelId) {
        CouncilProperties.ModelProps model = new CouncilProperties.ModelProps();
        model.setId(id);
        model.setProvider("ollama");
        model.setProviderModelId(providerModelId);
        model.setDefaultOutputTokens(outputTokens);
        model.setTimeoutSeconds(60);
        model.setRole(role);
        model.setModelFamily("family-" + id);
        return model;
    }
}
