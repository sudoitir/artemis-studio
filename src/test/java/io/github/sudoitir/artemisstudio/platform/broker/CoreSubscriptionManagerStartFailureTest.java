package io.github.sudoitir.artemisstudio.platform.broker;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.jms.JMSException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.Test;

/**
 * A notification subscription that fails to start is retried with backoff for as long as
 * its node stays unreachable, so each failed attempt must release what it built.
 */
class CoreSubscriptionManagerStartFailureTest {

    @Test
    void aFailedStartReleasesItsConnectionFactory() throws Exception {
        UUID clusterId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();

        ActiveMQConnectionFactory factory = mock(ActiveMQConnectionFactory.class);
        when(factory.createConnection()).thenThrow(new JMSException("AMQ219007: Cannot connect to server(s)"));
        CoreConnectionFactory connectionFactory = mock(CoreConnectionFactory.class);
        when(connectionFactory.build(any(), any())).thenReturn(factory);
        BrokerConnections connections = mock(BrokerConnections.class);
        when(connections.coreSettingsFor(any()))
                .thenReturn(new CoreConnectionSettings(clusterId, null, null, null, true));

        CoreSubscriptionManager manager =
                new CoreSubscriptionManager(connections, connectionFactory, new NotificationMapper(), List.of());

        manager.reconcile(clusterId, List.of(liveEndpoint(nodeId)));

        verify(factory).close();
    }

    private static NodeEndpoint liveEndpoint(UUID nodeId) {
        return new NodeEndpoint(
                nodeId,
                "primary",
                "artemis-node-1",
                null,
                "tcp://127.0.0.1:1",
                "PRIMARY",
                "STARTED",
                true,
                null,
                1L,
                "2.56.0",
                null,
                Instant.now(),
                false,
                false,
                false);
    }
}
