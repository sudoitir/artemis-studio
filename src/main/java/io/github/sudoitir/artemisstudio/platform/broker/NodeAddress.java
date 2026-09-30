package io.github.sudoitir.artemisstudio.platform.broker;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * A node's {@code host:port}, the only form of its address that telemetry carries: a Jolokia or
 * Core URL can hold {@code user:password@} and a path, and a metric tag or span attribute must
 * never have either.
 */
public final class NodeAddress {

    static final String UNKNOWN = "unknown";

    private NodeAddress() {}

    /**
     * @param url a Jolokia or Core URL, or a bare {@code host:port}; a Core failover list
     *     ({@code (tcp://a:1,tcp://b:2)}) is reduced to its first node
     * @return {@code host:port} (just the host when the URL has no port), or {@code unknown}
     */
    public static String hostPort(String url) {
        String dialable = CoreUrl.dialable(url);
        if (dialable == null) {
            return UNKNOWN;
        }
        try {
            URI uri = new URI(dialable.replaceFirst("[,)?].*$", "").replace("(", ""));
            if (uri.getHost() == null) {
                return UNKNOWN;
            }
            return uri.getPort() < 0 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
        } catch (URISyntaxException _) {
            return UNKNOWN;
        }
    }
}
