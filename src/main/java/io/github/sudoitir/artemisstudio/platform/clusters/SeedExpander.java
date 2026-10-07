package io.github.sudoitir.artemisstudio.platform.clusters;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Expands a seed whose host name resolves to several addresses into one seed per address, the way a
 * client expands {@code bootstrap.servers} (ADR-0175). A seed that resolves to one address, or to
 * one address of each family (a dual-stack host is still one host), or to none, stays as it is.
 *
 * <p>A TLS seed keeps its host name for the handshake and certificate check, so it stays the seed:
 * its addresses are expanded only to learn the NodeIDs behind the name, never to become the URLs
 * Studio stores.
 */
@Component
@Slf4j
public class SeedExpander {

    /** A seed to probe, and whether the node it reaches takes this URL as its own. */
    public record Seed(String url, boolean attachable) {}

    /** Resolves a host name to every address it has. */
    @FunctionalInterface
    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    /** The most seeds one host name expands to: a name with more addresses than a cluster has brokers is a mistake. */
    static final int MAX_ADDRESSES = 16;

    private final Resolver resolver;

    @Autowired
    public SeedExpander() {
        this(InetAddress::getAllByName);
    }

    public SeedExpander(Resolver resolver) {
        this.resolver = resolver;
    }

    public List<Seed> expand(String seedUrl) {
        URI uri = URI.create(seedUrl);
        List<Seed> seeds = new ArrayList<>();
        seeds.add(new Seed(seedUrl, true));
        if (uri.getHost() == null) {
            return seeds;
        }
        Set<String> addresses = new LinkedHashSet<>();
        int ipv4 = 0;
        try {
            for (InetAddress address : resolver.resolve(uri.getHost())) {
                if (addresses.add(address.getHostAddress()) && address instanceof Inet4Address) {
                    ipv4++;
                }
            }
        } catch (UnknownHostException e) {
            log.debug("Seed host {} does not resolve: {}", uri.getHost(), e.toString());
            return seeds;
        }
        // One address of each family is one host, reached over IPv4 and IPv6; several of a family are several hosts.
        if (Math.max(ipv4, addresses.size() - ipv4) < 2) {
            return seeds;
        }
        if (addresses.size() > MAX_ADDRESSES) {
            log.warn(
                    "Seed host {} resolves to {} addresses; only the first {} are used",
                    uri.getHost(),
                    addresses.size(),
                    MAX_ADDRESSES);
            addresses = addresses.stream().limit(MAX_ADDRESSES).collect(Collectors.toCollection(LinkedHashSet::new));
        }
        boolean tls = "https".equalsIgnoreCase(uri.getScheme());
        if (!tls) {
            seeds.clear();
        }
        for (String address : addresses) {
            seeds.add(new Seed(withHost(uri, address), !tls));
        }
        return seeds;
    }

    private static String withHost(URI uri, String host) {
        try {
            return new URI(uri.getScheme(), uri.getUserInfo(), host, uri.getPort(), uri.getPath(), uri.getQuery(), null)
                    .toString();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
