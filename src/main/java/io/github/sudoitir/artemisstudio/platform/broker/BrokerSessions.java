package io.github.sudoitir.artemisstudio.platform.broker;

import io.github.sudoitir.artemisstudio.kernel.replica.ReplicaSignal;
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

    /** A cluster was deleted on any replica, once its transaction committed: let go of it here. */
    @EventListener(condition = "#signal.kind() == 'cluster-deleted'")
    void onClusterDeleted(ReplicaSignal signal) {
        release(UUID.fromString(signal.key()));
    }
}
