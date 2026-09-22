package io.github.sudoitir.artemisstudio.platform.broker;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.SSLContext;
import org.apache.activemq.artemis.core.remoting.impl.netty.TransportConstants;
import org.apache.activemq.artemis.core.remoting.impl.ssl.DefaultSSLContextFactory;
import org.apache.activemq.artemis.spi.core.remoting.ssl.SSLContextConfig;
import org.apache.activemq.artemis.spi.core.remoting.ssl.SSLContextFactory;
import org.springframework.boot.ssl.NoSuchSslBundleException;
import org.springframework.boot.ssl.SslBundles;

/**
 * Resolves a Core connection's TLS trust from the cluster's Spring SSL bundle (ADR-0098), so every
 * cluster's connections trust only that cluster's authority and the JVM default {@link SSLContext}
 * is never touched.
 *
 * <p>Artemis finds this through {@code ServiceLoader} and picks it over its own default by
 * {@link #getPriority()}. A connection whose {@value TransportConstants#SSL_CONTEXT_PROP_NAME}
 * parameter names a bundle gets that bundle's context, built once per name and dropped when the
 * bundle is reloaded; any other connection is handled exactly as Artemis would.
 *
 * <p>{@code ServiceLoader} creates the instance, so it reaches Spring's {@link SslBundles} through
 * one static holder, set by {@link CoreConnectionFactory} when the application starts. This is the
 * one static seam between Spring and the Artemis SPI.
 */
public class StudioSslContextFactory implements SSLContextFactory {

    /** Above Artemis's default (5) and its caching variant (10). */
    static final int PRIORITY = 100;

    private static volatile SslBundles bundles;
    private static final Map<String, SSLContext> CONTEXTS = new ConcurrentHashMap<>();

    private final SSLContextFactory fallback = new DefaultSSLContextFactory();

    /** Point every Core connection at the application's bundles. Replaces any earlier holder and its contexts. */
    static void use(SslBundles sslBundles) {
        bundles = sslBundles;
        CONTEXTS.clear();
    }

    @Override
    public SSLContext getSSLContext(SSLContextConfig config, Map<String, Object> params) throws Exception {
        Object name = params == null ? null : params.get(TransportConstants.SSL_CONTEXT_PROP_NAME);
        if (name == null || name.toString().isBlank()) {
            return fallback.getSSLContext(config, params);
        }
        return contextFor(name.toString());
    }

    static SSLContext contextFor(String bundleName) {
        SslBundles current = bundles;
        if (current == null) {
            throw new IllegalStateException("The Core connection names SSL bundle '" + bundleName
                    + "', but Studio's SSL bundles are not available yet.");
        }
        return CONTEXTS.computeIfAbsent(bundleName, name -> {
            try {
                SSLContext context = current.getBundle(name).createSslContext();
                current.addBundleUpdateHandler(name, reloaded -> CONTEXTS.remove(name));
                return context;
            } catch (NoSuchSslBundleException e) {
                throw missing(name, e);
            }
        });
    }

    static IllegalStateException missing(String bundleName, Exception cause) {
        return new IllegalStateException(
                "TLS is configured for this cluster's Core connection but SSL bundle '" + bundleName
                        + "' is not defined. Add it under spring.ssl.bundle.",
                cause);
    }

    @Override
    public void clearSSLContexts() {
        CONTEXTS.clear();
    }

    @Override
    public int getPriority() {
        return PRIORITY;
    }
}
