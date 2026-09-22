package io.github.sudoitir.artemisstudio.platform.broker;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
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
}
