package io.github.sudoitir.artemisstudio.broker.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.config.ArtemisStudioProperties;
import io.github.sudoitir.artemisstudio.domain.topology.NodeEndpoint;
import io.github.sudoitir.artemisstudio.support.ArtemisIntegrationTest;
import jakarta.jms.Connection;
import jakarta.jms.Session;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ssl.SslBundles;

/**
 * The Core subscription reconciler against a real broker (ADR-0026): it follows
 * "who is live" and reports the right {@link SubscriptionVerdict}.
 */
class CoreSubscriptionManagerTest extends ArtemisIntegrationTest {

    private final CopyOnWriteArrayList<BrokerEvent> received = new CopyOnWriteArrayList<>();
    private CoreSubscriptionManager manager;
    private UUID clusterId;
    private UUID nodeId;

    @BeforeEach
    void setUp() {
        clusterId = UUID.randomUUID();
        nodeId = UUID.randomUUID();
        received.clear();

        ArtemisStudioProperties props =
                new ArtemisStudioProperties(null, null, null, null, null, null, null, null, null);
        CoreConnectionFactory connectionFactory = new CoreConnectionFactory(props, mock(SslBundles.class));

        BrokerConnections connections = mock(BrokerConnections.class);
        when(connections.coreSettingsFor(any()))
                .thenReturn(new CoreConnectionSettings(clusterId, BROKER_USER, BROKER_PASSWORD, null, true));

        List<BrokerEventSink> sinks = List.of(received::add);

        manager = new CoreSubscriptionManager(connections, connectionFactory, new NotificationMapper(), sinks);
    }

    @AfterEach
    void tearDown() {
        manager.forget(clusterId);
    }

    private NodeEndpoint endpoint(String coreUrl, boolean active) {
        return new NodeEndpoint(
                nodeId,
                "primary",
                "node-1",
                "http://localhost:8161/console/jolokia",
                coreUrl,
                "PRIMARY",
                "STARTED",
                active,
                null,
                1L,
                "2.44.0",
                null,
                Instant.now(),
                false,
                false,
                true);
    }

    @Test
    void subscribesToALiveNodeAndReceivesEvents() throws Exception {
        manager.reconcile(clusterId, List.of(endpoint(coreUrl(), true)));

        pollUntil(Duration.ofSeconds(15), () -> manager.verdictFor(clusterId).isConnected());

        provokeBrokerActivity();

        pollUntil(Duration.ofSeconds(10), () -> !received.isEmpty());
        assertThat(received).extracting(BrokerEvent::type).isNotEmpty();
    }

    @Test
    void followsFailoverByStoppingWhenTheNodeIsNoLongerLive() {
        manager.reconcile(clusterId, List.of(endpoint(coreUrl(), true)));
        pollUntil(Duration.ofSeconds(15), () -> manager.verdictFor(clusterId).isConnected());

        // Same node, no longer serving — the subscription must be dropped.
        manager.reconcile(clusterId, List.of(endpoint(coreUrl(), false)));

        pollUntil(Duration.ofSeconds(5), () -> !manager.verdictFor(clusterId).isConnected());
    }

    @Test
    void reportsFailedWhenTheCoreUrlIsUnreachable() {
        manager.reconcile(clusterId, List.of(endpoint("tcp://127.0.0.1:1", true)));

        pollUntil(Duration.ofSeconds(10), () -> manager.verdictFor(clusterId) instanceof SubscriptionVerdict.Failed);
    }

    @Test
    void reportsNotAttemptedForAnUnknownCluster() {
        assertThat(manager.verdictFor(UUID.randomUUID())).isInstanceOf(SubscriptionVerdict.NotAttempted.class);
    }

    private static void pollUntil(Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("condition not met within " + timeout);
    }

    private void provokeBrokerActivity() throws Exception {
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(
                coreUrl() + "?useTopologyForLoadBalancing=false", BROKER_USER, BROKER_PASSWORD);
        factory.setReconnectAttempts(0);
        try (Connection conn = factory.createConnection(BROKER_USER, BROKER_PASSWORD)) {
            conn.start();
            Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
            session.createConsumer(session.createQueue("core.sub.it." + System.currentTimeMillis()));
            session.close();
        } finally {
            factory.close();
        }
    }
}
