package io.github.sudoitir.artemisstudio.platform.broker;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.github.sudoitir.artemisstudio.kernel.replica.ReplicaSignal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BrokerSessionsTest {

    private final CoreSubscriptionManager subscriptions = mock(CoreSubscriptionManager.class);
    private final CorePool pool = mock(CorePool.class);
    private final CoreRelay relay = mock(CoreRelay.class);
    private final BrokerSessions sessions = new BrokerSessions(subscriptions, pool, relay);

    @Test
    void aClusterDeletedOnAnyReplicaIsReleasedHere() {
        UUID clusterId = UUID.randomUUID();

        sessions.onClusterDeleted(new ReplicaSignal("cluster-deleted", clusterId.toString()));

        verify(subscriptions).forget(clusterId);
        verify(pool).forget(clusterId);
        verify(relay).forget(clusterId);
    }
}
