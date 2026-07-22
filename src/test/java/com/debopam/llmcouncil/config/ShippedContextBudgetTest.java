package com.debopam.llmcouncil.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins that no shipped policy asks its chair to hold more evidence than fits.
 *
 * <p>Reducing {@code num-ctx}, adding a council member, or raising a member's
 * {@code defaultOutputTokens} can all silently push a policy back over budget.
 * The run still completes when that happens — it just synthesises from part of
 * the council's work — so nothing else in the suite would notice.
 */
@SpringBootTest
class ShippedContextBudgetTest {

    @Autowired
    private CouncilProperties props;

    @Value("${spring.ai.ollama.chat.options.num-ctx:4096}")
    private Integer ollamaNumCtx;

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
    void noShippedPolicyOverflowsItsChairContextWindow() {
        new CouncilConfigurationValidator(ollamaNumCtx).validate(props);

        List<String> overBudget = appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("Synthesis prompts will be truncated"))
                .toList();

        assertTrue(overBudget.isEmpty(),
                   "shipped policies whose chair cannot hold the council's evidence:\n"
                   + String.join("\n", overBudget));
    }
}
