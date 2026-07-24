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
@TestPropertySource(properties =
        "council.userConfigPath=src/test/resources/user-config/runtime-override.yml")
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
}
