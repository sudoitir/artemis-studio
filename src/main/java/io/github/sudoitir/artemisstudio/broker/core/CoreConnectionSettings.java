package io.github.sudoitir.artemisstudio.broker.core;

import java.util.UUID;

/**
 * Everything {@link CoreConnectionFactory} needs to open a Core connection for a
 * cluster: the (already decrypted) credentials and the optional TLS bundle name.
 * Like {@link io.github.sudoitir.artemisstudio.broker.BrokerConnectionSettings}
 * this is per cluster — {@code broker_credential} is keyed {@code (cluster_id,
 * kind)} — so one of these serves every node.
 *
 * <p>Resolution order for the credential is CORE, then JOLOKIA_BASIC, then
 * anonymous (ADR-0026): most operators run one broker account and never store a
 * separate CORE credential.
 */
public record CoreConnectionSettings(
        UUID clusterId, String username, String password, String tlsBundle, boolean verifyHostname) {

    public static CoreConnectionSettings anonymous(UUID clusterId) {
        return new CoreConnectionSettings(clusterId, null, null, null, true);
    }

    public boolean hasCredentials() {
        return username != null && !username.isBlank();
    }

    public boolean hasTls() {
        return tlsBundle != null && !tlsBundle.isBlank();
    }
}
