package io.github.sudoitir.artemisstudio.platform.broker;

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

    /** Bytes of messages a Core consumer or browser buffers ahead of reading them. */
    static final int CONSUMER_WINDOW_BYTES = 64 * 1024;

    private final BrokerProperties properties;
    private final SslBundles sslBundles;

    public CoreConnectionFactory(BrokerProperties properties, SslBundles sslBundles) {
        this.properties = properties;
        this.sslBundles = sslBundles;
    }

    public ActiveMQConnectionFactory build(CoreConnectionSettings settings, String dialableCoreUrl) {
        // A bounded prefetch: the default 1 MiB per consumer and browser is buffered in Studio
        // whether or not it is read, which a browse of one page or a paused capture drain
        // never needs (core-transport spec).
        String url = dialableCoreUrl + "?useTopologyForLoadBalancing=false;consumerWindowSize=" + CONSUMER_WINDOW_BYTES;
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
        factory.setCallTimeout(properties.readTimeout().toMillis());
        // The client pings once per failure-check period and the broker drops a connection that
        // sends nothing for a TTL, so the TTL must outlast several pings. With the defaults a 20s
        // TTL against a 30s ping closed every idle capture connection, and every one whose
        // listener was waiting on the database.
        factory.setClientFailureCheckPeriod(properties.readTimeout().toMillis());
        factory.setConnectionTTL(properties.readTimeout().toMillis() * 3);
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
