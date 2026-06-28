package com.debopam.llmcouncil.model;

import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Immutable registry of model profiles and their backing clients.
 *
 * <p><b>Gap 6.1 (ModelRegistry Immutability):</b> previously, this class used
 * mutable state populated via a {@code register()} method called from
 * {@code @PostConstruct}. The component was technically usable before
 * registration, which is a construction anti-pattern. This refactored version
 * takes the completed maps via the constructor, making the registry fully
 * immutable once built.
 *
 * <p>The registry is now created as a {@code @Bean} in
 * {@link com.debopam.llmcouncil.config.CouncilConfig} rather than being a
 * {@code @Component} with mutable state.
 *
 * @see com.debopam.llmcouncil.config.CouncilConfig#modelRegistry()
 */
public class ModelRegistry {

    // Immutable, defensively copied maps — set once at construction time.
    private final Map<String, ModelProfile> profiles;
    private final Map<String, ModelClient> clients;

    /**
     * Create a fully-initialized registry.
     *
     * @param profiles Map of model ID → profile metadata. Defensively copied.
     * @param clients  Map of model ID → client implementation. Defensively copied.
     * @throws NullPointerException if either argument is null.
     */
    public ModelRegistry(Map<String, ModelProfile> profiles, Map<String, ModelClient> clients) {
        // Defensive copy ensures the registry cannot be modified after construction.
        this.profiles = Map.copyOf(profiles);
        this.clients = Map.copyOf(clients);
    }

    /**
     * Look up a model profile by its logical ID.
     *
     * @param id The model's logical ID (e.g., "ollama-llama3", "oci-gpt-5-4").
     * @return The model profile.
     * @throws NoSuchElementException if the model ID is not registered.
     */
    public ModelProfile model(String id) {
        var p = profiles.get(id);
        if (p == null) {
            throw new NoSuchElementException("Unknown model: " + id);
        }
        return p;
    }

    /**
     * Look up the client implementation for a model by its logical ID.
     *
     * @param id The model's logical ID.
     * @return The model client for making calls.
     * @throws NoSuchElementException if no client is configured for the model.
     */
    public ModelClient clientForModel(String id) {
        var c = clients.get(id);
        if (c == null) {
            throw new NoSuchElementException("No client configured for model: " + id);
        }
        return c;
    }

    /**
     * @return Number of registered model profiles.
     */
    public int size() {
        return profiles.size();
    }
}
