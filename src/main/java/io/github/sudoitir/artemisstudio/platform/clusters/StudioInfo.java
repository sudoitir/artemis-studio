package io.github.sudoitir.artemisstudio.platform.clusters;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import io.github.sudoitir.artemisstudio.kernel.plugin.StudioVersion;
import io.github.sudoitir.artemisstudio.kernel.security.PermissionResolver;
import io.github.sudoitir.artemisstudio.kernel.security.Permissions;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Facts about this installation that a plugin may state, for example in the header of a
 * file it exports. A cluster's name is given only to a caller the cluster list would show
 * it to, so an unknown id and a hidden cluster answer the same.
 */
@Component
@PluginApi
@RequiredArgsConstructor
public class StudioInfo {

    private final StudioVersion studioVersion;
    private final ClusterDirectory clusters;
    private final PermissionResolver perm;

    /** The running Studio version, such as {@code 2026.10.1}; empty for a development build. */
    public Optional<String> version() {
        return studioVersion.current().map(Object::toString);
    }

    /** The cluster's display name, when the current caller holds {@code cluster:read} on it. */
    public Optional<String> clusterName(UUID clusterId) {
        if (clusterId == null || !perm.can(clusterId, Permissions.CLUSTER_READ)) {
            return Optional.empty();
        }
        return clusters.cluster(clusterId).map(RegisteredCluster::getName);
    }
}
