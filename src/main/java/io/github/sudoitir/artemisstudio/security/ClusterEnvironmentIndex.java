package io.github.sudoitir.artemisstudio.security;

import io.github.sudoitir.artemisstudio.persist.ClusterEntity;
import io.github.sudoitir.artemisstudio.persist.ClusterRepository;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * A small in-memory {@code clusterId -> environmentId} map, so
 * {@link PermissionResolver} can walk cluster -> environment scope without a
 * query per permission check (design.md decision 3). The cluster set is tiny
 * and already fully resident in memory for the scrape scheduler; this mirrors
 * that assumption. Invalidated on any cluster write.
 */
@Component
@RequiredArgsConstructor
public class ClusterEnvironmentIndex {

    private final ClusterRepository clusters;
    private volatile Map<UUID, UUID> index;

    /** Cluster id -> environment id (absent if the cluster has none, or does not exist). */
    public UUID environmentOf(UUID clusterId) {
        return current().get(clusterId);
    }

    /** Call after any create/update/delete that could change a cluster's environment. */
    public void invalidate() {
        index = null;
    }

    private Map<UUID, UUID> current() {
        Map<UUID, UUID> snapshot = index;
        if (snapshot == null) {
            snapshot = new ConcurrentHashMap<>();
            for (ClusterEntity c : clusters.findAllByOrderByNameAsc()) {
                if (c.getEnvironmentId() != null) {
                    snapshot.put(c.getId(), c.getEnvironmentId());
                }
            }
            index = snapshot;
        }
        return snapshot;
    }
}
