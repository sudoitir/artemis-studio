package io.github.sudoitir.artemisstudio.feature.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.feature.flow.ClientEdges.Kind;
import io.github.sudoitir.artemisstudio.feature.flow.FlowStore.StoredEdge;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerClientFactory;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerConnectionSettings;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.artemisstudio.support.ArtemisIntegrationTest;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import jakarta.jms.Connection;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import java.util.List;
import java.util.UUID;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * {@link ClientSampler} against a real Artemis 2.44 broker (task 3.10): the listing fields the
 * sampler reads exist, two sweeps give producer and consumer rates, and a row cap below the
 * client count is reported as truncation rather than hidden.
 */
class FlowSamplerIT extends PostgresIntegrationTest {

    @Autowired
    ClientSampler sampler;

    @Autowired
    FlowStore store;

    @Autowired
    BrokerClientFactory clients;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerNodeRepository nodes;

    /** The real client for the container, under the credentials a registered cluster would carry. */
    @MockitoBean
    BrokerConnections connections;

    private UUID clusterId;
    private UUID nodeId;

    @BeforeEach
    void register() {
        String jolokia = ArtemisIntegrationTest.jolokiaUrl();
        clusterId = clusters.save(new ClusterEntity("flow-it-" + UUID.randomUUID(), null, null))
                .getId();
        BrokerNodeEntity node = BrokerNodeEntity.fromSeed(
                clusterId, "artemis", "PRIMARY", UUID.randomUUID().toString());
        node.attachManagementUrl(jolokia);
        nodeId = nodes.save(node).getId();
        when(connections.forCluster(eq(clusterId), any()))
                .thenAnswer(inv -> clients.forNode(
                        BrokerConnectionSettings.basicAuth(
                                clusterId, ArtemisIntegrationTest.BROKER_USER, ArtemisIntegrationTest.BROKER_PASSWORD),
                        jolokia));
    }

    @AfterEach
    void cleanUp() {
        clusters.deleteById(clusterId);
    }

    @Test
    void twoSweepsOfARealBrokerGiveProducerAndConsumerRates() throws Exception {
        String queue = "flow.it." + System.nanoTime();
        var factory = new ActiveMQConnectionFactory(
                ArtemisIntegrationTest.coreUrl() + "?useTopologyForLoadBalancing=false",
                ArtemisIntegrationTest.BROKER_USER,
                ArtemisIntegrationTest.BROKER_PASSWORD);
        try (Connection connection = factory.createConnection()) {
            connection.setClientID("flow-it-app");
            connection.start();
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            MessageProducer producer = session.createProducer(session.createQueue(queue));
            MessageConsumer consumer = session.createConsumer(session.createQueue(queue));

            sampler.sweep(clusterId);
            for (int i = 0; i < 50; i++) {
                producer.send(session.createTextMessage("m" + i));
                assertThat(consumer.receive(2_000)).isNotNull();
            }
            Thread.sleep(1_100);
            sampler.sweep(clusterId);

            List<StoredEdge> edges = store.edges(clusterId).stream()
                    .filter(e -> queue.equals(e.edge().address()))
                    .toList();
            assertThat(edges).extracting(e -> e.edge().kind()).containsExactlyInAnyOrder(Kind.PRODUCE, Kind.CONSUME);
            assertThat(edges).allSatisfy(e -> {
                assertThat(e.edge().clientId()).isEqualTo("flow-it-app");
                assertThat(e.edge().rate()).isNotNull().isPositive();
                assertThat(e.edge().memberCount()).isEqualTo(1);
            });
            assertThat(store.nodeSamples(clusterId))
                    .singleElement()
                    .satisfies(s -> assertThat(s.errorKind()).isNull());
        }
    }

    @Test
    void aRowCapBelowTheClientCountIsReportedAsTruncation() throws Exception {
        String queue = "flow.it.cap." + System.nanoTime();
        var factory = new ActiveMQConnectionFactory(
                ArtemisIntegrationTest.coreUrl() + "?useTopologyForLoadBalancing=false",
                ArtemisIntegrationTest.BROKER_USER,
                ArtemisIntegrationTest.BROKER_PASSWORD);
        try (Connection connection = factory.createConnection()) {
            connection.start();
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            session.createConsumer(session.createQueue(queue));
            session.createConsumer(session.createQueue(queue));

            var node = nodes.findById(nodeId).orElseThrow();
            ClientSampler.NodeResult result = sampler.sampleNode(clusterId, node, 1);

            assertThat(result.truncated()).isTrue();
            assertThat(result.sample().consumersSeen()).isEqualTo(1);
            assertThat(result.sample().consumersTotal()).isGreaterThanOrEqualTo(2);
        }
    }
}
