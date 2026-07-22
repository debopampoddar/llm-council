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
 */
public class CouncilCatalogHolder {

    private final AtomicReference<CouncilCatalog> current = new AtomicReference<>();

    /** Create an uninitialised holder. Spring uses this constructor. */
    public CouncilCatalogHolder() {
    }

    /**
     * Create a holder that is immediately ready to read.
     *
     * <p>Intended for tests and for direct construction outside the Spring
     * context.
     *
     * @param initial the catalog this holder starts with; must not be null
     */
    public CouncilCatalogHolder(CouncilCatalog initial) {
        this.current.set(Objects.requireNonNull(initial, "initial"));
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

    /** @return {@code true} once a catalog has been installed */
    public boolean isInitialised() {
        return current.get() != null;
    }

    /**
     * Install the catalog built at startup.
     *
     * <p>Called once by {@link CouncilConfig}. Replacing an existing catalog is
     * rejected: live reload is a deliberate, separately-designed operation and
     * must not happen by accident through this method.
     *
     * @param catalog the catalog to install; must not be null
     * @throws IllegalStateException if a catalog is already installed
     */
    public void initialise(CouncilCatalog catalog) {
        Objects.requireNonNull(catalog, "catalog");
        if (!current.compareAndSet(null, catalog)) {
            throw new IllegalStateException(
                    "CouncilCatalog is already initialised; use a reload operation to replace it.");
        }
    }
}
