package io.github.sudoitir.artemisstudio.platform.clusters;

import java.net.URI;
import java.util.regex.Pattern;

/**
 * A cluster's management URL pattern (ADR-0175): a seed URL with the host replaced by
 * {@code {host}}, such as {@code http://{host}:8161/console/jolokia}. A node's management URL is the
 * pattern with the host of its connector in place of the placeholder; only a broker answering there
 * with the node's NodeID is accepted as that node.
 */
public final class ManagementUrlPattern {

    public static final String HOST = "{host}";

    private static final Pattern SHAPE = Pattern.compile("https?://\\{host}(?::\\d{1,5})?(?:/[^?#{}\\s@]*)?");

    private ManagementUrlPattern() {}

    /** The pattern a first seed implies: its own scheme, port and path, any host; {@code null} for a URL that is none. */
    public static String defaultFor(String seedUrl) {
        URI uri;
        try {
            uri = URI.create(seedUrl);
        } catch (IllegalArgumentException _) {
            return null;
        }
        if (uri.getScheme() == null || uri.getHost() == null) {
            return null;
        }
        StringBuilder pattern = new StringBuilder(uri.getScheme()).append("://").append(HOST);
        if (uri.getPort() > 0) {
            pattern.append(':').append(uri.getPort());
        }
        if (uri.getRawPath() != null) {
            pattern.append(uri.getRawPath());
        }
        return pattern.toString();
    }

    /** Whether a seed URL carries an account (`user:password@host`), which belongs in the account fields instead. */
    public static boolean hasUserInfo(String url) {
        try {
            return URI.create(url).getRawUserInfo() != null;
        } catch (IllegalArgumentException _) {
            return false;
        }
    }

    /** The URL of the node whose connector is on {@code host}. */
    public static String derive(String pattern, String host) {
        return pattern.replace(HOST, host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host);
    }

    /**
     * Whether the pattern is exactly {@code http(s)://{host}[:port][/path]}: the placeholder is the whole host and
     * appears nowhere else, and there is no user-info, query or fragment. A pattern is where Studio sends the
     * management account, so one that could name another host (`http://evil/?h={host}`) is refused.
     */
    public static boolean isValid(String pattern) {
        return pattern != null && SHAPE.matcher(pattern).matches();
    }

    /** The host of a {@code host:port} connector name. */
    public static String connectorHost(String connector) {
        int colon = connector.lastIndexOf(':');
        return colon > 0 ? connector.substring(0, colon) : connector;
    }
}
