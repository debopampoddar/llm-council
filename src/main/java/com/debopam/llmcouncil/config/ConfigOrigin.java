package com.debopam.llmcouncil.config;

/**
 * Provenance of a single configuration entity in the merged {@link CouncilCatalog}.
 *
 * <p>Origin is tracked from Phase 0 onward even though only {@link #BUILT_IN}
 * can occur until the user configuration overlay lands. Recording it now means
 * the catalog contract does not change when the overlay is introduced.
 */
public enum ConfigOrigin {

    /** Shipped in {@code application.yml}. Validated fail-fast at boot. */
    BUILT_IN,

    /** Defined by the user with an id that does not exist in the built-in set. */
    USER,

    /** Defined by the user with an id that shadows a built-in entity. */
    USER_OVERRIDE
}
