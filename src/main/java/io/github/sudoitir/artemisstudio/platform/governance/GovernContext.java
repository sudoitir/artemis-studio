package io.github.sudoitir.artemisstudio.platform.governance;

import java.util.UUID;

/**
 * Who is reading what: the cluster and address a message came from, and whether the caller holds
 * {@code message:clear} there. Resolved once per request by {@link ContentPolicy#context}.
 */
public record GovernContext(UUID clusterId, String address, boolean clearAccess) {

    /** For stored copies: nobody is reading, so everything sensitive is masked. */
    public static GovernContext masked(UUID clusterId, String address) {
        return new GovernContext(clusterId, address, false);
    }
}
