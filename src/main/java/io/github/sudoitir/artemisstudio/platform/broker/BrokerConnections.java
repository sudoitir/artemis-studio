package io.github.sudoitir.artemisstudio.platform.broker;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Builds a {@link JolokiaBrokerClient} for a registered cluster from the settings its
 * {@link ConnectionSettingsSource} resolves, then hands off to {@link BrokerClientFactory}.
 */
@Component
@RequiredArgsConstructor
public class BrokerConnections {

    private final BrokerClientFactory factory;
    private final ConnectionSettingsSource settings;

    public JolokiaBrokerClient forCluster(UUID clusterId, String jolokiaUrl) {
        return factory.forNode(settingsFor(clusterId), jolokiaUrl);
    }

    public BrokerConnectionSettings settingsFor(UUID clusterId) {
        return settings.jolokiaSettings(clusterId);
    }

    public CoreConnectionSettings coreSettingsFor(UUID clusterId) {
        return settings.coreSettings(clusterId);
    }
}
