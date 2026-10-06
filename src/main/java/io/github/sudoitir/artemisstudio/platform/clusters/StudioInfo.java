package io.github.sudoitir.artemisstudio.platform.clusters;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import io.github.sudoitir.artemisstudio.kernel.plugin.StudioVersion;
import io.github.sudoitir.artemisstudio.kernel.security.PermissionResolver;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
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

    /**
     * How many broker instances this installation manages: every node of every registered cluster,
     * backups included. A count only, with no names, so it is the same for every caller. A plugin may
     * size a license by it, or anything else.
     */
    public int brokerInstances() {
        return clusters.allNodes().size();
    }

    /**
     * The cluster's display name, when the current caller may see it: they hold {@code cluster:read} on
     * it, or a team of theirs has queues or addresses on it.
     */
    public Optional<String> clusterName(UUID clusterId) {
        if (clusterId == null || !perm.canSeeCluster(clusterId)) {
            return Optional.empty();
        }
        return clusters.cluster(clusterId).map(RegisteredCluster::getName);
    }

    /**
     * The clusters the current caller may see (as {@link #clusterName}), by id, with their display
     * names, ordered by name. Names are not unique, so a plugin that accepts a name must say when
     * it matches more than one cluster.
     */
    public Map<UUID, String> clusters() {
        Map<UUID, String> visible = new LinkedHashMap<>();
        clusters.clusters().stream()
                .filter(c -> perm.canSeeCluster(c.getId()))
                .sorted(Comparator.comparing(RegisteredCluster::getName, String.CASE_INSENSITIVE_ORDER))
                .forEach(c -> visible.put(c.getId(), c.getName()));
        return visible;
    }
}
