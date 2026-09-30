package io.github.sudoitir.artemisstudio.kernel.replica;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** The bus: transactional publish, the three shapes, the size cap and the reconnect. */
class StudioBusTest extends PostgresIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    DataSourceProperties datasource;

    @Autowired
    PlatformTransactionManager transactions;

    private final List<Object> received = new CopyOnWriteArrayList<>();
    private StudioBus bus;

    @BeforeEach
    void listen() {
        bus = new StudioBus(jdbc, mapper, datasource, received::add);
        bus.start();
        await().atMost(Duration.ofSeconds(10)).until(bus::isListening);
    }

    @AfterEach
    void close() {
        bus.stop();
    }

    @Test
    void theWireNamesShapesNotClasses() {
        String json = mapper.writeValueAsString(new ReplicaSignal("settings", "k"));

        assertThat(json).contains("\"t\":\"signal\"").doesNotContain("ReplicaSignal");
    }

    @Test
    void aMessageIsSentWhenItsTransactionCommits() throws Exception {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            bus.publish(new ReplicaSignal("settings", "committed"));
            assertThat(sleepAndSnapshot())
                    .as("nothing is sent before the commit")
                    .isEmpty();
        });

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(received).containsExactly(new ReplicaSignal("settings", "committed")));
    }

    @Test
    void aRolledBackMessageIsNeverSent() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            bus.publish(new ReplicaSignal("settings", "rolled-back"));
            status.setRollbackOnly();
        });
        bus.publish(new ReplicaSignal("settings", "after"));

        await().atMost(Duration.ofSeconds(5)).until(() -> !received.isEmpty());
        assertThat(received).containsExactly(new ReplicaSignal("settings", "after"));
    }

    @Test
    void framesAndEventsRoundTrip() {
        UUID cluster = UUID.randomUUID();
        BusFrame frame = new BusFrame(cluster, "queues", mapper.readTree("{\"a\":1}"), "7");
        bus.publish(frame);
        bus.publish(new BusEvents(List.of(4L, 5L)));

        await().atMost(Duration.ofSeconds(5)).until(() -> received.size() == 2);
        assertThat(received).containsExactly(frame, new BusEvents(List.of(4L, 5L)));
    }

    @Test
    void aFrameTooLargeForTheBusLosesItsData() {
        UUID cluster = UUID.randomUUID();
        bus.publish(new BusFrame(cluster, "big", mapper.valueToTree("x".repeat(9_000)), "9"));

        await().atMost(Duration.ofSeconds(5)).until(() -> !received.isEmpty());
        assertThat(received).containsExactly(new BusFrame(cluster, "big", null, "9"));
    }

    @Test
    void manyEventsAreSplitAcrossMessages() {
        List<Long> seqs =
                LongStream.range(1_000_000_000L, 1_000_001_500L).boxed().toList();
        bus.publish(new BusEvents(seqs));

        await().atMost(Duration.ofSeconds(5))
                .until(() -> received.stream()
                                .map(BusEvents.class::cast)
                                .mapToInt(e -> e.seqs().size())
                                .sum()
                        == seqs.size());
        assertThat(received.stream().map(BusEvents.class::cast).flatMap(e -> e.seqs().stream()))
                .containsExactlyElementsOf(seqs);
    }

    @Test
    void theBusReconnectsAndSaysSo() {
        jdbc.queryForList(
                "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name = 'studio-bus'");

        await().atMost(Duration.ofSeconds(15)).until(() -> received.contains(new BusResumed()));
        await().atMost(Duration.ofSeconds(5)).until(bus::isListening);
        assertThat(bus.downSince()).isEmpty();
        bus.publish(new ReplicaSignal("settings", "again"));
        await().atMost(Duration.ofSeconds(5)).until(() -> received.contains(new ReplicaSignal("settings", "again")));
    }

    private List<Object> sleepAndSnapshot() {
        try {
            Thread.sleep(400);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return List.copyOf(received);
    }
}
