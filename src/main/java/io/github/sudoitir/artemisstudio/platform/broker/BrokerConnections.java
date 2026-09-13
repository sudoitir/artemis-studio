package io.github.sudoitir.artemisstudio.platform.broker;

import io.github.sudoitir.artemisstudio.kernel.security.SecretVault;
import io.github.sudoitir.artemisstudio.platform.clusters.BrokerCredentialEntity;
import io.github.sudoitir.artemisstudio.platform.clusters.BrokerCredentialRepository;
import io.github.sudoitir.artemisstudio.platform.clusters.BrokerTlsEntity;
import io.github.sudoitir.artemisstudio.platform.clusters.BrokerTlsRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Builds a {@link JolokiaBrokerClient} for a persisted cluster: resolves the
 * stored {@code JOLOKIA_BASIC} credential (decrypting it through {@link SecretVault},
 * ADR-0009) and the {@code broker_tls} SSL-bundle name, then hands off to
 * {@link BrokerClientFactory}.
 *
 * <p>Registration builds its own {@link BrokerConnectionSettings} straight from
 * the request body — nothing is persisted or encrypted until the cluster exists.
 */
@Component
@RequiredArgsConstructor
public class BrokerConnections {

    private static final String JOLOKIA_BASIC = "JOLOKIA_BASIC";
    private static final String CORE = "CORE";

    private final BrokerClientFactory factory;
    private final BrokerCredentialRepository credentials;
    private final BrokerTlsRepository tlsRepository;
    private final SecretVault vault;

    public JolokiaBrokerClient forCluster(UUID clusterId, String jolokiaUrl) {
        return factory.forNode(settingsFor(clusterId), jolokiaUrl);
    }

    public BrokerConnectionSettings settingsFor(UUID clusterId) {
        String username = null;
        String password = null;
        var credential = credentials.findByClusterIdAndKind(clusterId, JOLOKIA_BASIC);
        if (credential.isPresent()) {
            username = credential.get().getUsername();
            password = vault.decrypt(
                    clusterId,
                    JOLOKIA_BASIC,
                    credential.get().getSecretCt(),
                    credential.get().getSecretNonce());
        }

        BrokerTlsEntity tls = tlsRepository.findByClusterId(clusterId).orElse(null);
        String bundle = tls != null ? tls.getTruststoreRef() : null;
        boolean verifyHostname = tls == null || tls.isVerifyHostname();

        return new BrokerConnectionSettings(clusterId, username, password, bundle, verifyHostname);
    }

    /**
     * Core-connection settings for a cluster: the stored {@code CORE} credential
     * if there is one, otherwise the {@code JOLOKIA_BASIC} credential, otherwise
     * anonymous (ADR-0026, D6). Each row is decrypted with the kind it was sealed
     * under — the {@link SecretVault} AAD is {@code clusterId|kind}.
     */
    public CoreConnectionSettings coreSettingsFor(UUID clusterId) {
        BrokerCredentialEntity credential = credentials
                .findByClusterIdAndKind(clusterId, CORE)
                .or(() -> credentials.findByClusterIdAndKind(clusterId, JOLOKIA_BASIC))
                .orElse(null);

        BrokerTlsEntity tls = tlsRepository.findByClusterId(clusterId).orElse(null);
        String bundle = tls != null ? tls.getTruststoreRef() : null;
        boolean verifyHostname = tls == null || tls.isVerifyHostname();

        if (credential == null) {
            return new CoreConnectionSettings(clusterId, null, null, bundle, verifyHostname);
        }
        String password =
                vault.decrypt(clusterId, credential.getKind(), credential.getSecretCt(), credential.getSecretNonce());
        return new CoreConnectionSettings(clusterId, credential.getUsername(), password, bundle, verifyHostname);
    }
}
