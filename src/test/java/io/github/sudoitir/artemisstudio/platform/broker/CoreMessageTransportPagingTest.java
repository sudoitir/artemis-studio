package io.github.sudoitir.artemisstudio.platform.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.platform.broker.MessageTransport.BrowseResult;
import io.github.sudoitir.artemisstudio.platform.broker.MessageTransport.TransportTarget;
import io.github.sudoitir.artemisstudio.support.ArtemisIntegrationTest;
import jakarta.jms.Connection;
import jakarta.jms.Session;
import java.time.Duration;
import java.util.UUID;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * A queue whose address is paging, against a real broker. The JMS browser ends its
 * enumeration when the next message is not already on the client ({@code receiveImmediate}),
 * which on a paging queue happens part-way through. Measured on the dev stack, a queue of 120
 * messages read over Core came back as pages of 50, 50 and 0, or 50, 46 and 20, or a first
 * page of 100 holding 32 — each time with the broker counting 120.
 */
class CoreMessageTransportPagingTest extends ArtemisIntegrationTest {

    private static final int MESSAGES = 120;

    private CoreMessageTransport transport;
    private String queueName;
    private UUID clusterId;
    private JolokiaBrokerClient client;

    @BeforeEach
    void setUp() throws Exception {
        clusterId = UUID.randomUUID();
        queueName = "core.paging.it." + System.nanoTime();

        RestClient rest = RestClient.builder()
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().setBasicAuth(BROKER_USER, BROKER_PASSWORD);
                    return execution.execute(request, body);
                })
                .build();
        client =
                new JolokiaBrokerClient(rest, jolokiaUrl(), JsonMapper.builder().build());

        BrokerConnections connections = mock(BrokerConnections.class);
        when(connections.coreSettingsFor(any()))
                .thenReturn(new CoreConnectionSettings(clusterId, BROKER_USER, BROKER_PASSWORD, null, true));
        when(connections.forCluster(any(), any())).thenReturn(client);

        BrokerProperties props = new BrokerProperties(Duration.ofSeconds(3), Duration.ofSeconds(10), 2_000);
        MessageOperations messageOps = new MessageOperations();
        transport = new CoreMessageTransport(
                connections,
                new CorePool(new CoreConnectionFactory(props, mock(SslBundles.class))),
                new JolokiaMessageTransport(connections, new MessageBrowser(), messageOps),
                new NodeCallLimiter(
                        new RateLimitProperties(1_000), new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                messageOps);

        // 20 KiB before the address pages; 120 messages of 1 KiB page almost all of them.
        client.execOnBroker(
                "addAddressSettings(java.lang.String,java.lang.String)",
                queueName,
                "{\"maxSizeBytes\":20480,\"pageSizeBytes\":10240,\"addressFullMessagePolicy\":\"PAGE\"}");
        seed();
    }

    private void seed() throws Exception {
        var factory = new ActiveMQConnectionFactory(
                coreUrl() + "?useTopologyForLoadBalancing=false", BROKER_USER, BROKER_PASSWORD);
        try (Connection conn = factory.createConnection(BROKER_USER, BROKER_PASSWORD)) {
            Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
            var producer = session.createProducer(session.createQueue(queueName));
            String body = "x".repeat(1024);
            for (int i = 0; i < MESSAGES; i++) {
                producer.send(session.createTextMessage(i + " " + body));
            }
            session.close();
        } finally {
            factory.close();
        }
    }

    private TransportTarget target() {
        return new TransportTarget(
                clusterId, UUID.randomUUID(), queueName, queueName, "ANYCAST", jolokiaUrl(), coreUrl());
    }

    @Test
    void everyPageOfAPagingQueueHoldsWhatTheBrokerCounts() {
        String address = BrokerMBeans.address(client.resolveBrokerObjectName(), queueName);
        assertThat(client.single(JolokiaRequest.read(address, "Paging"))
                        .attribute("Paging")
                        .asBoolean())
                .as("precondition: the address is paging")
                .isTrue();

        // The shortfall depends on how far the broker has depaged when the browser asks, so
        // one read proves little; ten reads of each page, every one complete, does.
        for (int round = 0; round < 10; round++) {
            for (int page = 1; page <= 2; page++) {
                BrowseResult result = transport.browse(target(), page, 100, null);
                assertThat(result.page().total()).isEqualTo(MESSAGES);
                assertThat(result.page().messages())
                        .as("round %d, page %d", round, page)
                        .hasSize(page == 1 ? 100 : MESSAGES - 100);
            }
        }
    }
}
