package com.debopam.llmcouncil.config.user;

import com.debopam.llmcouncil.config.CouncilCatalogHolder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves the overlay's {@code runtime:} section reaches the running application.
 *
 * <p>These knobs were previously read straight from {@code @Value} at the point
 * of use, so the overlay could declare them, pass validation, and change
 * nothing. Configuration that is accepted and then silently ignored is worse
 * than configuration that is refused: the user has no way to tell the two apart.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "council.userConfigPath=src/test/resources/user-config/runtime-override.yml",
        // Deliberately not the 90 that both application.yml and
        // RetentionSettings.DEFAULTS carry. With those equal, a merge that
        // wrongly rebuilt retention from defaults would be indistinguishable
        // from one that correctly kept the configured value, and the
        // "unmentioned bound survives" assertion below would prove nothing.
        "council.persistence.retention.max-age-days=45"
})
class UserConfigRuntimeTest {

    @Autowired
    private CouncilCatalogHolder catalogHolder;

    @Test
    void concurrencyLimitComesFromTheOverlay() {
        assertEquals(3, catalogHolder.get().runtime().maxConcurrentRuns(),
                     "application.yml ships 1; the overlay asked for 3");
    }

    @Test
    void chatHistoryDepthComesFromTheOverlay() {
        assertEquals(9, catalogHolder.get().runtime().chatRecentTurnCount(),
                     "application.yml ships 4; the overlay asked for 9");
    }

    @Test
    void unmentionedKnobsKeepTheirShippedValues() {
        // The overlay set only the two counts. Merging must not blank the
        // artifact path as a side effect of mentioning its siblings.
        String artifactPath = catalogHolder.get().runtime().artifactBasePath();

        assertEquals(false, artifactPath == null || artifactPath.isBlank(),
                     "artifactBasePath was not mentioned and must survive the merge");
    }

    @Test
    void retentionBoundsComeFromTheOverlay() {
        // The same failure this class was written for, one level down. Retention
        // is carried on the catalog rather than read from @Value precisely so
        // that this assertion can hold; a @Value read would validate the
        // overlay's numbers and then run on the shipped ones.
        var retention = catalogHolder.get().runtime().retention();

        assertEquals(25, retention.maxSessions(), "application.yml ships 500; the overlay asked for 25");
        assertEquals(750, retention.maxEventsPerSession(),
                     "application.yml ships 2000; the overlay asked for 750");
    }

    @Test
    void anUnmentionedRetentionBoundKeepsItsConfiguredValue() {
        // The overlay set maxSessions and maxEventsPerSession but not this one.
        // Configured to 45 rather than the shipped 90 on purpose: 90 is also the
        // built-in default, so asserting 90 would pass even if the merge had
        // thrown the configured block away and started from defaults.
        assertEquals(45, catalogHolder.get().runtime().retention().maxAgeDays(),
                     "maxAgeDays was not mentioned, so it keeps the configured value rather "
                     + "than being reset by the merge");
    }
}
