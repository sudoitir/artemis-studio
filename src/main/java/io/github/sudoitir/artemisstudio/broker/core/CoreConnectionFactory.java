package io.github.sudoitir.artemisstudio.broker.core;

import io.github.sudoitir.artemisstudio.config.ArtemisStudioProperties;
import javax.net.ssl.SSLContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.springframework.boot.ssl.NoSuchSslBundleException;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.stereotype.Component;

/**
 * Builds an {@link ActiveMQConnectionFactory} for a node's Core URL (ADR-0026).
 *
 * <ul>
 *   <li>{@code useTopologyForLoadBalancing=false} — the broker pushes its
 *       topology to CORE clients and advertises {@code <connector>} hosts Studio
 *       often cannot resolve; without this a blocking call wedges on a reconnect
 *       loop against the unresolvable host.
 *   <li>{@code initialConnectAttempts=1}, {@code reconnectAttempts=0} — Studio
 *       drives its own reconnect ({@link CoreSubscriptionManager}) so a wedged
 *       node never blocks a caller.
 * </ul>
 */
@Component
@Slf4j
public class CoreConnectionFactory {

    private final ArtemisStudioProperties properties;
    private final SslBundles sslBundles;

    public CoreConnectionFactory(ArtemisStudioProperties properties, SslBundles sslBundles) {
        this.properties = properties;
        this.sslBundles = sslBundles;
    }

    public ActiveMQConnectionFactory build(CoreConnectionSettings settings, String dialableCoreUrl) {
        String url = dialableCoreUrl + "?useTopologyForLoadBalancing=false";
        if (settings.hasTls()) {
            // ponytail: one shared default SSLContext for every Core connection. Per-connection
            // broker trust material would need a custom Artemis SSLContextFactory; add that only
            // if a deployment actually presents distinct broker CAs.
            installDefaultSslContext(settings.tlsBundle());
            url += ";sslEnabled=true;useDefaultSslContext=true";
        }
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(url);
        if (settings.hasCredentials()) {
            factory.setUser(settings.username());
            factory.setPassword(settings.password());
        }
        factory.setInitialConnectAttempts(1);
        factory.setReconnectAttempts(0);
        factory.setCallTimeout(properties.broker().readTimeout().toMillis());
        factory.setConnectionTTL(properties.broker().readTimeout().toMillis() * 2);
        return factory;
    }

    private void installDefaultSslContext(String bundleName) {
        try {
            SSLContext context = sslBundles.getBundle(bundleName).createSslContext();
            SSLContext.setDefault(context);
        } catch (NoSuchSslBundleException e) {
            throw new IllegalStateException(
                    "TLS is configured for this cluster's Core connection but SSL bundle '" + bundleName
                            + "' is not defined. Add it under spring.ssl.bundle.",
                    e);
        }
    }
}
