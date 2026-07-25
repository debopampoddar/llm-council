package com.debopam.llmcouncil.config;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the currently active {@link CouncilCatalog}.
 *
 * <p>Every consumer of configuration reads through this holder rather than
 * injecting the underlying maps, which is what allows the whole control plane
 * to be replaced atomically later. In this phase the catalog is written exactly
 * once at boot and never changes; the indirection exists so that adding live
 * reload does not require touching every consumer again.
 *
 * <p><b>Reader contract:</b> call {@link #get()} once and work from the returned
 * reference for the duration of the operation. Calling it repeatedly within one
 * logical unit of work would defeat the purpose of the snapshot.
 *
 * <p>Created as a bean by {@link CouncilConfig#councilCatalogHolder}, not by
 * component scanning, so that anything injecting it transitively depends on a
 * fully-built catalog and can never observe an uninitialised holder.
 *
 * <p>The holder keeps <b>two</b> snapshots. {@link #get()} returns the catalog
 * the application runs from, which is the built-in configuration with the user
 * overlay merged over it. {@link #builtIn()} returns the catalog before that
 * merge. Both are needed because validating a <em>proposed</em> overlay against
 * the active catalog would resolve the currently-applied overlay's own entities
 * as though they were shipped: a draft that dropped a model would validate
 * cleanly and then orphan a policy at the next boot, and a draft that redeclared
 * its own protocol would be rejected for colliding with a built-in that does not
 * exist. Only the built-in catalog is a stable reference to validate against.
 */
public class CouncilCatalogHolder {

    private final AtomicReference<CouncilCatalog> current = new AtomicReference<>();
    private final AtomicReference<CouncilCatalog> builtIn = new AtomicReference<>();

    /** Create an uninitialised holder. Spring uses this constructor. */
    public CouncilCatalogHolder() {
    }

    /**
     * Create a holder whose active and built-in catalogs are the same snapshot.
     *
     * <p>Intended for tests and for direct construction outside the Spring
     * context. It is also the accurate description of an installation with no
     * overlay file, where nothing was merged over the shipped configuration.
     *
     * @param initial the catalog this holder starts with; must not be null
     */
    public CouncilCatalogHolder(CouncilCatalog initial) {
        this(initial, initial);
    }

    /**
     * Create a holder that is immediately ready to read.
     *
     * @param builtIn the catalog built from {@code application.yml} alone; must
     *                not be null
     * @param active  the catalog to run from, overlay included; must not be null
     */
    public CouncilCatalogHolder(CouncilCatalog builtIn, CouncilCatalog active) {
        this.builtIn.set(Objects.requireNonNull(builtIn, "builtIn"));
        this.current.set(Objects.requireNonNull(active, "active"));
    }

    /**
     * Return the active catalog.
     *
     * @return the current configuration snapshot
     * @throws IllegalStateException if the holder has not been initialised yet,
     *                               which indicates a component read
     *                               configuration before {@code CouncilConfig}
     *                               finished building it
     */
    public CouncilCatalog get() {
        CouncilCatalog catalog = current.get();
        if (catalog == null) {
            throw new IllegalStateException(
                    "CouncilCatalog has not been initialised. A component read configuration "
                    + "before CouncilConfig finished building the catalog.");
        }
        return catalog;
    }

    /**
     * Return the catalog as shipped, before any user overlay was merged.
     *
     * <p>This is the reference a proposed overlay is validated and previewed
     * against. It is never what a council run reads — runs read {@link #get()} —
     * because a user's own models and policies are as real to a run as the
     * shipped ones.
     *
     * @return the built-in configuration snapshot
     * @throws IllegalStateException if the holder has not been initialised yet
     */
    public CouncilCatalog builtIn() {
        CouncilCatalog catalog = builtIn.get();
        if (catalog == null) {
            throw new IllegalStateException(
                    "CouncilCatalog has not been initialised. A component read configuration "
                    + "before CouncilConfig finished building the catalog.");
        }
        return catalog;
    }

    /** @return {@code true} once a catalog has been installed */
    public boolean isInitialised() {
        return current.get() != null;
    }

    /**
     * Install the catalog built at startup, with no overlay applied.
     *
     * <p>Called once by {@link CouncilConfig}. Replacing an existing catalog is
     * rejected: live reload is a deliberate, separately-designed operation and
     * must not happen by accident through this method.
     *
     * @param catalog the catalog to install as both built-in and active; must
     *                not be null
     * @throws IllegalStateException if a catalog is already installed
     */
    public void initialise(CouncilCatalog catalog) {
        initialise(catalog, catalog);
    }

    /**
     * Install both snapshots built at startup.
     *
     * @param builtInCatalog the catalog built from {@code application.yml} alone
     * @param activeCatalog  the catalog to run from, overlay included
     * @throws IllegalStateException if a catalog is already installed
     */
    public void initialise(CouncilCatalog builtInCatalog, CouncilCatalog activeCatalog) {
        Objects.requireNonNull(builtInCatalog, "builtInCatalog");
        Objects.requireNonNull(activeCatalog, "activeCatalog");
        if (!current.compareAndSet(null, activeCatalog)) {
            throw new IllegalStateException(
                    "CouncilCatalog is already initialised; use a reload operation to replace it.");
        }
        builtIn.set(builtInCatalog);
    }
}
