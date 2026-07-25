package com.debopam.llmcouncil.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The holder keeps the shipped catalog separate from the one runs read.
 *
 * <p>This matters for the configuration write path. A proposed overlay has to be
 * validated against configuration that is stable — the shipped one — because the
 * active catalog already has the <em>current</em> overlay merged into it. Against
 * the active catalog a draft that dropped a user model would still resolve every
 * policy that referenced it, and a draft that redeclared its own protocol would
 * be rejected for colliding with a built-in that does not exist.
 *
 * <p>The overlay fixture is loaded deliberately so that each assertion here has a
 * positive control: {@code my-critic} must be present in one snapshot and absent
 * from the other. With no overlay loaded, every absence assertion below would
 * pass for the wrong reason.
 */
@SpringBootTest
@TestPropertySource(properties =
        "council.userConfigPath=src/test/resources/user-config/partially-invalid.yml")
class BuiltInCatalogSeamTest {

    @Autowired
    private CouncilCatalogHolder holder;

    @Test
    void theActiveCatalogCarriesTheOverlay() {
        // Positive control for every absence assertion below.
        assertTrue(holder.get().modelRegistry().findModel("my-critic").isPresent(),
                   "the fixture overlay did not apply, so the built-in assertions prove nothing");
        assertTrue(holder.get().profiles().containsKey("my-council"));
        assertTrue(holder.get().policies().containsKey("my-balanced"));
    }

    @Test
    void theBuiltInCatalogDoesNotCarryTheOverlay() {
        CouncilCatalog builtIn = holder.builtIn();

        assertFalse(builtIn.modelRegistry().findModel("my-critic").isPresent(),
                    "a user model leaked into the built-in snapshot");
        assertFalse(builtIn.profiles().containsKey("my-council"));
        assertFalse(builtIn.policies().containsKey("my-balanced"));
    }

    @Test
    void theBuiltInCatalogStillCarriesShippedConfiguration() {
        CouncilCatalog builtIn = holder.builtIn();

        assertTrue(builtIn.profiles().containsKey("mock"),
                   "the built-in snapshot must be complete, not merely overlay-free");
        assertTrue(builtIn.protocols().containsKey("rigorous"));
        assertEquals(1L, builtIn.generation(),
                     "the shipped catalog is generation 1; the overlay produces the next one");
    }

    @Test
    void theTwoSnapshotsAreTheSameWhenNothingWasMerged() {
        CouncilCatalog only = holder.builtIn();
        CouncilCatalogHolder unmerged = new CouncilCatalogHolder(only);

        assertEquals(only, unmerged.get());
        assertEquals(only, unmerged.builtIn());
    }
}
