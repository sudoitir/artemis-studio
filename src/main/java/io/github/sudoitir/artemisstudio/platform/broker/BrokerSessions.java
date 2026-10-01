package io.github.sudoitir.artemisstudio.platform.broker;

import io.github.sudoitir.artemisstudio.kernel.replica.ReplicaSignal;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** The per-cluster transport state the broker holds open between calls. */
@Component
@RequiredArgsConstructor
public class BrokerSessions {

    private final CoreSubscriptionManager subscriptions;
    private final CorePool pool;
    private final CoreRelay relay;

    /**
     * Release a removed cluster's Core connections and drop its in-memory subscription state,
     * so it is not retried.
     */
    public void release(UUID clusterId) {
        subscriptions.forget(clusterId);
        pool.forget(clusterId);
        relay.forget(clusterId);
    }

    /**
     * Let go of every cluster not in {@code existing}. The {@code cluster-deleted} signal is lost while the
     * bus is down, so after it is back whatever this still holds for a cluster that is gone is released.
     */
    public void releaseAllBut(Set<UUID> existing) {
        Set<UUID> gone = new HashSet<>(subscriptions.clusterIds());
        gone.addAll(pool.clusterIds());
        gone.addAll(relay.clusterIds());
        gone.removeAll(existing);
        gone.forEach(this::release);
    }

    /** A cluster was deleted on any replica, once its transaction committed: let go of it here. */
    @EventListener(condition = "#signal.kind() == 'cluster-deleted'")
    void onClusterDeleted(ReplicaSignal signal) {
        release(UUID.fromString(signal.key()));
    }
}
