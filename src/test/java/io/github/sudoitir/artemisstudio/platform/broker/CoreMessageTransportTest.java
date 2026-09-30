package io.github.sudoitir.artemisstudio.platform.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.platform.broker.MessageBrowser.BodyEncoding;
import io.github.sudoitir.artemisstudio.platform.broker.MessageBrowser.BrowsedMessage;
import io.github.sudoitir.artemisstudio.platform.broker.MessageTransport.BrowseResult;
import io.github.sudoitir.artemisstudio.platform.broker.MessageTransport.Channel;
import io.github.sudoitir.artemisstudio.platform.broker.MessageTransport.SendSpec;
import io.github.sudoitir.artemisstudio.platform.broker.MessageTransport.TransportTarget;
import io.github.sudoitir.artemisstudio.support.ArtemisIntegrationTest;
import jakarta.jms.BytesMessage;
import jakarta.jms.Connection;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ssl.SslBundles;

/**
 * {@link CoreMessageTransport} against a real broker (ADR-0029): a
 * {@link jakarta.jms.QueueBrowser} returns real byte bodies and typed
 * properties, does not truncate, and a page past the bounded depth falls back to
 * Jolokia.
 */
class CoreMessageTransportTest extends ArtemisIntegrationTest {

    private CoreMessageTransport transport;
    private JolokiaMessageTransport jolokiaFallback;
    private String queueName;

    @BeforeEach
    void setUp() throws Exception {
        UUID clusterId = UUID.randomUUID();
        queueName = "core.tx.it." + System.nanoTime();

        BrokerProperties props = new BrokerProperties(Duration.ofSeconds(3), Duration.ofSeconds(10), 2_000);
        CoreConnectionFactory connectionFactory = new CoreConnectionFactory(props, mock(SslBundles.class));
        CorePool corePool = new CorePool(connectionFactory, CoreObservations.none());

        BrokerConnections connections = mock(BrokerConnections.class);
        when(connections.coreSettingsFor(any()))
                .thenReturn(new CoreConnectionSettings(clusterId, BROKER_USER, BROKER_PASSWORD, null, true));

        jolokiaFallback = mock(JolokiaMessageTransport.class);
        when(jolokiaFallback.browse(any(), any(Integer.class), any(Integer.class), any()))
                .thenReturn(new BrowseResult(new MessageBrowser.BrowsePage(List.of(), 0), Channel.JOLOKIA));

        transport = new CoreMessageTransport(
                connections,
                corePool,
                jolokiaFallback,
                new NodeCallLimiter(
                        new RateLimitProperties(1_000), new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                new MessageOperations(),
                CoreObservations.none());

        seed();
    }

    private TransportTarget target() {
        return new TransportTarget(
                UUID.randomUUID(), UUID.randomUUID(), queueName, queueName, "ANYCAST", null, coreUrl());
    }

    private void seed() throws Exception {
        var factory = new ActiveMQConnectionFactory(
                coreUrl() + "?useTopologyForLoadBalancing=false", BROKER_USER, BROKER_PASSWORD);
        try (Connection conn = factory.createConnection(BROKER_USER, BROKER_PASSWORD)) {
            conn.start();
            Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
            var queue = session.createQueue(queueName);
            var producer = session.createProducer(queue);

            TextMessage text = session.createTextMessage("hello core");
            text.setStringProperty("orderId", "A-1");
            text.setLongProperty("attempts", 4L);
            producer.send(text);

            BytesMessage bytes = session.createBytesMessage();
            bytes.writeBytes(new byte[] {1, 2, 3, 4, 5});
            bytes.setStringProperty("big", "x".repeat(4096)); // far over the 256-byte management limit
            producer.send(bytes);

            BytesMessage json = session.createBytesMessage();
            json.writeBytes(JSON.getBytes(StandardCharsets.UTF_8));
            producer.send(json);

            BytesMessage gzipped = session.createBytesMessage();
            gzipped.writeBytes(gzip(JSON));
            producer.send(gzipped);

            session.close();
        } finally {
            factory.close();
        }
    }

    private static final String JSON = "{\"orderId\":\"A-1\",\"status\":\"FAILED\"}";

    private static byte[] gzip(String text) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    @Test
    void browseReturnsFaithfulBodiesAndTypedPropertiesWithNoTruncation() {
        BrowseResult result = transport.browse(target(), 1, 50, null);

        assertThat(result.servedBy()).isEqualTo(Channel.CORE);
        assertThat(result.page().messages()).hasSize(4);
        assertThat(result.page().messages())
                .allSatisfy(m -> assertThat(m.bodyTruncated()).isFalse());

        var textMsg = result.page().messages().stream()
                .filter(m -> m.type() == 3)
                .findFirst()
                .orElseThrow();
        assertThat(textMsg.body()).isEqualTo("hello core");
        assertThat(textMsg.stringProperties()).containsEntry("orderId", "A-1");
        assertThat(textMsg.longProperties()).containsEntry("attempts", 4L);

        var bytesMsg = result.page().messages().stream()
                .filter(m -> m.bodyEncoding() == BodyEncoding.BASE64)
                .findFirst()
                .orElseThrow();
        assertThat(Base64.getDecoder().decode(bytesMsg.body())).containsExactly(1, 2, 3, 4, 5);
        assertThat(bytesMsg.stringProperties().get("big")).hasSize(4096); // not clipped
    }

    @Test
    void textSentAsBytesIsReadAsTextAndKeepsItsType() throws IOException {
        List<BrowsedMessage> decoded = transport.browse(target(), 1, 50, null).page().messages().stream()
                .filter(m -> JSON.equals(m.body()))
                .toList();

        assertThat(decoded).hasSize(2).allSatisfy(m -> {
            assertThat(m.type()).isEqualTo(4);
            assertThat(m.bodyEncoding()).isEqualTo(BodyEncoding.TEXT);
        });
        assertThat(decoded)
                .extracting(BrowsedMessage::bodyCompression, BrowsedMessage::size)
                .containsExactlyInAnyOrder(
                        tuple(BodyDecoder.Compression.NONE, (long) JSON.length()),
                        tuple(BodyDecoder.Compression.GZIP, (long) gzip(JSON).length));
    }

    @Test
    void aPageReadsOnlyThatPageAndStatesAnUnavailableTotalRatherThanCountingTheQueue() {
        BrowseResult result = transport.browse(target(), 1, 1, null);

        assertThat(result.servedBy()).isEqualTo(Channel.CORE);
        assertThat(result.page().messages()).hasSize(1);
        // This target has no management URL, so the broker's count cannot be read: it is
        // stated as unavailable, never replaced by the number of messages Studio walked.
        assertThat(result.page().total()).isNull();
        assertThat(result.page().totalUnavailable()).contains("no management URL");
    }

    @Test
    void aSampleReadsAtMostItsLimit() {
        assertThat(transport.sample(target(), 1)).hasSize(1);
    }

    @Test
    void aDeepPageFallsBackToJolokia() {
        BrowseResult result = transport.browse(target(), 10, 50, null);

        assertThat(result.servedBy()).isEqualTo(Channel.JOLOKIA);
    }

    @Test
    void sendOverCorePlacesABytesMessage() {
        transport.send(
                target(),
                new SendSpec(4, true, Base64.getEncoder().encodeToString(new byte[] {9, 9}), true, Map.of(), Map.of()));

        BrowseResult result = transport.browse(target(), 1, 50, null);
        assertThat(result.page().messages()).hasSize(5);
    }
}
