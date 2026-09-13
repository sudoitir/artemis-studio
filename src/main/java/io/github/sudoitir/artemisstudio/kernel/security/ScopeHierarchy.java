package io.github.sudoitir.artemisstudio.kernel.security;

import java.util.UUID;

/**
 * Where a cluster sits in the grant scope walk (ADR-0038): cluster, then its
 * environment, then global. Implemented by the module that owns clusters, so the
 * security kernel resolves scopes without depending on it.
 */
public interface ScopeHierarchy {

    /** The cluster's environment id, or {@code null} when it has none or does not exist. */
    UUID environmentOf(UUID clusterId);

    /** The cluster's name, or {@code null} when it does not exist. Audit records it beside the id. */
    String clusterName(UUID clusterId);
}
