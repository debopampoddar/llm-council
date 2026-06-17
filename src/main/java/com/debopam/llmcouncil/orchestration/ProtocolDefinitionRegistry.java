package com.debopam.llmcouncil.orchestration;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Holds all named {@link ProtocolDefinition}s loaded from configuration.
 *
 * <p>Populated by {@link com.debopam.llmcouncil.config.CouncilConfig} at startup.
 */
@Component
public class ProtocolDefinitionRegistry {

    private Map<String, ProtocolDefinition> protocols = Map.of();

    /**
     * Called once at startup with the full set of protocols.
     *
     * @param protocols Protocol ID → definition map.
     */
    public void register(Map<String, ProtocolDefinition> protocols) {
        this.protocols = Map.copyOf(protocols);
    }

    /**
     * Look up a protocol by ID.
     *
     * @param protocolId The protocol identifier.
     * @return The registered {@link ProtocolDefinition}.
     * @throws NoSuchElementException if not found.
     */
    public ProtocolDefinition get(String protocolId) {
        ProtocolDefinition p = protocols.get(protocolId);
        if (p == null) throw new NoSuchElementException(
                "No protocol registered with id '" + protocolId
                + "'. Known protocols: " + protocols.keySet());
        return p;
    }

    /** @return {@code true} if a protocol with this ID exists. */
    public boolean contains(String protocolId) {
        return protocols.containsKey(protocolId);
    }
}
