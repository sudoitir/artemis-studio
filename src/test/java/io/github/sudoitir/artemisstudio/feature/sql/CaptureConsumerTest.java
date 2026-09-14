package io.github.sudoitir.artemisstudio.feature.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.platform.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerProperties;
import io.github.sudoitir.artemisstudio.platform.broker.CoreConnectionFactory;
import io.github.sudoitir.artemisstudio.platform.broker.CoreConnectionSettings;
import io.github.sudoitir.artemisstudio.platform.broker.CorePool;
import io.github.sudoitir.artemisstudio.support.ArtemisIntegrationTest;
import jakarta.jms.Connection;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * Store first, acknowledge second (ADR-0077), against a real broker: a failed store loses
 * nothing and stores nothing twice, and one drain's acknowledgement never covers rows another
 * drain has not stored.
 */
class CaptureConsumerTest extends ArtemisIntegrationTest {

    private static final int BATCH = 50;

    private final UUID clusterId = UUID.randomUUID();
    private final UUID nodeId = UUID.randomUUID();
    private final UUID subscriptionId = UUID.randomUUID();
    private final List<MessageIndexWriter.Captured> stored = new CopyOnWriteArrayList<>();

    private CorePool pool;
    private MessageIndexWriter writer;
    private CaptureConsumer consumer;

    @BeforeEach
    void setUp() {
        BrokerProperties properties = new BrokerProperties(Duration.ofSeconds(3), Duration.ofSeconds(10), 2_000);
        pool = new CorePool(new CoreConnectionFactory(properties, mock(SslBundles.class)));
        BrokerConnections connections = mock(BrokerConnections.class);
        when(connections.coreSettingsFor(any()))
                .thenReturn(new CoreConnectionSettings(clusterId, BROKER_USER, BROKER_PASSWORD, null, true));
        writer = mock(MessageIndexWriter.class);
        consumer = new CaptureConsumer(pool, connections, new CaptureBus(), writer);
    }

    @AfterEach
    void tearDown() {
        consumer.closeAll();
        pool.closeAll();
    }

    @Test
    void aFailedStoreIsRedeliveredAndStoredOnceWithoutLoss() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
                    List<MessageIndexWriter.Captured> batch = List.copyOf(invocation.getArgument(0));
                    if (calls.getAndIncrement() < 2) {
                        throw new DataAccessResourceFailureException("database unavailable");
                    }
                    stored.addAll(batch);
                    return null;
                })
                .when(writer)
                .capturedBatch(anyList());
        String queue = "capture.test." + UUID.randomUUID();
        send(queue, 3 * BATCH);

        consumer.start(spec(queue, "ORDER.IN"));

        awaitTrue(() -> stored.size() >= 3 * BATCH, Duration.ofSeconds(60));
        Set<Long> ids = stored.stream().map(c -> c.row().messageId()).collect(Collectors.toSet());
        assertThat(ids).hasSize(3 * BATCH);
        assertThat(stored).hasSize(3 * BATCH);
        assertThat(consumer.takeShortfall(nodeId, subscriptionId).storeFailure())
                .contains("database unavailable");
    }

    @Test
    void oneDrainNeverAcknowledgesRowsAnotherDrainHasNotStored() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        List<List<String>> batchAddresses = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
                    List<MessageIndexWriter.Captured> batch = List.copyOf(invocation.getArgument(0));
                    batchAddresses.add(batch.stream()
                            .map(c -> c.row().address())
                            .distinct()
                            .toList());
                    if (batch.getFirst().row().address().equals("SLOW")) {
                        release.await(30, TimeUnit.SECONDS);
                    }
                    stored.addAll(batch);
                    return null;
                })
                .when(writer)
                .capturedBatch(anyList());
        String slow = "capture.slow." + UUID.randomUUID();
        String fast = "capture.fast." + UUID.randomUUID();
        send(slow, BATCH);
        send(fast, BATCH);

        consumer.start(spec(slow, "SLOW"));
        consumer.start(spec(fast, "FAST"));

        awaitTrue(() -> count("FAST") == BATCH, Duration.ofSeconds(30));
        assertThat(count("SLOW")).isZero();
        release.countDown();
        awaitTrue(() -> count("SLOW") == BATCH, Duration.ofSeconds(30));
        // Every write carried one drain's rows only: no drain can commit, or acknowledge, another's.
        assertThat(batchAddresses).allSatisfy(addresses -> assertThat(addresses).hasSize(1));
    }

    private long count(String address) {
        return stored.stream().filter(c -> c.row().address().equals(address)).count();
    }

    private CaptureConsumer.Spec spec(String queue, String sourceAddress) {
        return new CaptureConsumer.Spec(
                clusterId,
                nodeId,
                "primary",
                coreUrl(),
                subscriptionId,
                "tap." + queue,
                queue,
                sourceAddress,
                10_000,
                10_000);
    }

    private static void send(String queue, int count) throws Exception {
        try (ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(coreUrl());
                Connection connection = factory.createConnection(BROKER_USER, BROKER_PASSWORD)) {
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            MessageProducer producer = session.createProducer(session.createQueue(queue));
            for (int i = 0; i < count; i++) {
                producer.send(session.createTextMessage("message " + i));
            }
        }
    }

    private static void awaitTrue(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition not met within " + timeout);
            }
            Thread.sleep(100);
        }
    }
}
