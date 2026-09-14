package io.github.sudoitir.artemisstudio.platform.broker;

import java.util.UUID;

/**
 * Where the broker transport learns how to reach a registered cluster: its decrypted
 * credentials and TLS bundle. Implemented by the module that owns cluster registration, so
 * the transport never reads that module's tables.
 */
public interface ConnectionSettingsSource {

    /** Jolokia settings: the stored {@code JOLOKIA_BASIC} credential and the TLS bundle, if any. */
    BrokerConnectionSettings jolokiaSettings(UUID clusterId);

    /**
     * Core settings: the stored {@code CORE} credential if there is one, otherwise the
     * {@code JOLOKIA_BASIC} credential, otherwise anonymous (ADR-0026, D6).
     */
    CoreConnectionSettings coreSettings(UUID clusterId);
}
