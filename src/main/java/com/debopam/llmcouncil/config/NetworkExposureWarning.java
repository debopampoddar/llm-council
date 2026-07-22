package com.debopam.llmcouncil.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Warns at startup when the API is reachable from outside this machine.
 *
 * <p>The application has no authentication. Loopback binding is therefore the
 * only thing preventing anyone on the network from reading configuration,
 * starting runs, and (once configuration writes land) changing which models the
 * council uses. Binding to any other address is a legitimate deployment choice —
 * containers require it — but it must be a visible one rather than something a
 * user discovers later.
 */
@Component
public class NetworkExposureWarning {

    private static final Logger log = LoggerFactory.getLogger(NetworkExposureWarning.class);

    private static final Set<String> LOOPBACK_ADDRESSES = Set.of("127.0.0.1", "::1", "localhost");

    private final String bindAddress;

    /**
     * @param bindAddress the configured {@code server.address}, blank when unset
     */
    public NetworkExposureWarning(@Value("${server.address:}") String bindAddress) {
        this.bindAddress = bindAddress;
    }

    /** Log the exposure warning once the server is accepting requests. */
    @EventListener(ApplicationReadyEvent.class)
    public void warnIfExposed() {
        String address = bindAddress == null ? "" : bindAddress.trim();
        if (address.isEmpty() || LOOPBACK_ADDRESSES.contains(address)) {
            return;
        }
        log.warn("LLM Council is bound to {} and is reachable beyond this machine. "
                 + "The API has no authentication: anyone who can reach this port can read the "
                 + "configuration catalog and start council runs. Restrict access at the network "
                 + "level, or set LLM_COUNCIL_BIND_ADDRESS=127.0.0.1 to bind loopback only.",
                 address);
    }
}
