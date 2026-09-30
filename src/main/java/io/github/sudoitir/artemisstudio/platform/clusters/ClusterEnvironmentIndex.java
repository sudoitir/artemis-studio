package io.github.sudoitir.artemisstudio.platform.clusters;

import io.github.sudoitir.artemisstudio.kernel.replica.BusResumed;
import io.github.sudoitir.artemisstudio.kernel.replica.ReplicaSignal;
import io.github.sudoitir.artemisstudio.kernel.replica.StudioBus;
import io.github.sudoitir.artemisstudio.kernel.security.PermissionResolver;
import io.github.sudoitir.artemisstudio.kernel.security.ScopeHierarchy;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.ClusterRepository;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * A small in-memory {@code clusterId -> environmentId} map, so
 * {@link PermissionResolver} can walk cluster -> environment scope without a
 * query per permission check (design.md decision 3). The cluster set is tiny
 * and already fully resident in memory for the scrape scheduler; this mirrors
 * that assumption. Invalidated on any cluster write, on every replica.
 */
@Component
@RequiredArgsConstructor
public class ClusterEnvironmentIndex implements ScopeHierarchy {

    private final ClusterRepository clusters;
    private final StudioBus bus;
    private volatile Map<UUID, UUID> index;

    /** Cluster id -> environment id (absent if the cluster has none, or does not exist). */
    @Override
    public UUID environmentOf(UUID clusterId) {
        return current().get(clusterId);
    }

    @Override
    public String clusterName(UUID clusterId) {
        return clusters.findById(clusterId).map(ClusterEntity::getName).orElse(null);
    }

    /**
     * Call after any create/update/delete that could change a cluster's environment. Drops the map
     * here at once, and on every replica (this one again) when the writing transaction commits.
     */
    public void invalidate() {
        index = null;
        bus.publish(new ReplicaSignal("env-index", ""));
    }

    @EventListener(condition = "#signal.kind() == 'env-index'")
    void onSignal(ReplicaSignal signal) {
        index = null;
    }

    /** The bus was down: a cluster may have moved environment in the gap. */
    @EventListener
    void onBusResumed(BusResumed resumed) {
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
