package io.github.sudoitir.artemisstudio.feature.events;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.kernel.replica.ReplicaSignal;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerEvent;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link BrokerEventWriter}: {@code accept} never blocks, overflow is counted per
 * cluster, and {@code flush} persists the buffer in one batch.
 *
 * <p>Each test uses its own writer. The application's writer is drained every second by the
 * {@code events-flush} job, which would empty the buffer between two accepts and make overflow a
 * race.
 */
class BrokerEventWriterTest extends PostgresIntegrationTest {

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    ObjectProvider<BrokerEventPublisher> publisher;

    @Autowired
    EventsProperties properties;

    @Autowired
    PlatformTransactionManager transactions;

    private final UUID clusterId = UUID.randomUUID();
    private BrokerEventWriter writer;

    @BeforeEach
    void seedCluster() {
        jdbc.update(
                "INSERT INTO cluster (id, name) VALUES (:id, :name)",
                Map.of("id", clusterId, "name", "writer-" + clusterId));
        writer = new BrokerEventWriter(jdbc, mapper, publisher, transactions, properties);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM cluster WHERE id = :id", Map.of("id", clusterId));
    }

    private BrokerEvent event(String type) {
        return event(clusterId, type);
    }

    private BrokerEvent event(UUID cluster, String type) {
        return new BrokerEvent(
                cluster,
                null,
                type,
                Instant.now(),
                "some.address",
                "rn",
                "c1",
                "s1",
                "conn1",
                "1.2.3.4:5",
                "alice",
                Map.of("_AMQ_NotifType", type, "_AMQ_Address", "some.address"));
    }

    private int persisted() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM broker_event WHERE cluster_id = :c", Map.of("c", clusterId), Integer.class);
    }

    @Test
    void flushPersistsTheBufferedBatch() {
        writer.accept(event("CONSUMER_CREATED"));
        writer.accept(event("SESSION_CREATED"));

        writer.flush();

        assertThat(persisted()).isEqualTo(2);
        String storedProps = jdbc.queryForObject(
                "SELECT props::text FROM broker_event WHERE cluster_id = :c AND type = 'CONSUMER_CREATED'",
                Map.of("c", clusterId),
                String.class);
        assertThat(storedProps).contains("_AMQ_Address");
    }

    @Test
    void aBatchWhoseWriteFailsIsKeptInOrderForTheNextFlush() {
        NamedParameterJdbcTemplate failingOnce = org.mockito.Mockito.spy(jdbc);
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("database down"))
                .doCallRealMethod()
                .when(failingOnce)
                .batchUpdate(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(
                                org.springframework.jdbc.core.namedparam.SqlParameterSource[].class),
                        org.mockito.ArgumentMatchers.any(org.springframework.jdbc.support.KeyHolder.class),
                        org.mockito.ArgumentMatchers.any(String[].class));
        BrokerEventWriter flaky = new BrokerEventWriter(failingOnce, mapper, publisher, transactions, properties);
        flaky.accept(event("CONSUMER_CREATED"));
        flaky.accept(event("SESSION_CREATED"));

        org.assertj.core.api.Assertions.assertThatThrownBy(flaky::flush).hasMessageContaining("database down");
        assertThat(persisted()).isZero();

        flaky.flush();

        assertThat(persisted()).isEqualTo(2);
        assertThat(flaky.droppedFor(clusterId)).isZero();
        assertThat(jdbc.queryForList(
                        "SELECT type FROM broker_event WHERE cluster_id = :c ORDER BY seq",
                        Map.of("c", clusterId),
                        String.class))
                .containsExactly("CONSUMER_CREATED", "SESSION_CREATED");
    }

    @Test
    void thePublisherGetsOnlyItsOwnRows() {
        List<String> published = new ArrayList<>();
        ObjectProvider<BrokerEventPublisher> capturing = Mockito.mock();
        Mockito.when(capturing.getIfAvailable())
                .thenReturn((seqs, batch) -> batch.forEach(e -> published.add(e.type())));
        BrokerEventWriter capturingWriter = new BrokerEventWriter(jdbc, mapper, capturing, transactions, properties);
        TransactionTemplate tx = new TransactionTemplate(transactions);
        capturingWriter.accept(event("CONSUMER_CREATED"));
        capturingWriter.accept(event("SESSION_CREATED"));

        tx.executeWithoutResult(status -> {
            capturingWriter.flush();
            // Another writer's row lands in the same window; it is not ours to publish.
            writer.accept(event("BINDING_ADDED"));
            writer.flush();
        });

        assertThat(published).containsExactly("CONSUMER_CREATED", "SESSION_CREATED");
    }

    @Test
    void overflowIsDroppedAndCountedNotBlocked() {
        writer.setCapacity(1);
        writer.accept(event("CONSUMER_CREATED")); // fills the soft cap
        writer.accept(event("CONSUMER_CLOSED")); // dropped
        writer.accept(event("SESSION_CREATED")); // dropped

        assertThat(writer.droppedFor(clusterId)).isEqualTo(2);

        writer.flush();
        assertThat(persisted()).isEqualTo(1);
    }

    @Test
    void anIntegrityViolatingBatchDoesNotBlockTheEventsBehindIt() {
        UUID deleted = UUID.randomUUID();
        writer.accept(event(deleted, "CONSUMER_CREATED"));
        writer.accept(event("SESSION_CREATED"));

        writer.flush();
        writer.accept(event("CONSUMER_CLOSED"));
        writer.flush();

        assertThat(jdbc.queryForList(
                        "SELECT type FROM broker_event WHERE cluster_id = :c ORDER BY seq",
                        Map.of("c", clusterId),
                        String.class))
                .containsExactly("SESSION_CREATED", "CONSUMER_CLOSED");
        // A deleted cluster has no total to report, and a counter made for it now would outlive its deletion signal.
        assertThat(writer.droppedFor(deleted)).isZero();
        assertThat(writer.droppedFor(clusterId)).isZero();
    }

    @Test
    void aDeletedClustersBufferedEventsArePurged() {
        UUID deleted = UUID.randomUUID();
        writer.accept(event(deleted, "CONSUMER_CREATED"));
        writer.accept(event("SESSION_CREATED"));

        writer.onClusterDeleted(new ReplicaSignal("cluster-deleted", deleted.toString()));
        writer.flush();

        assertThat(persisted()).isEqualTo(1);
        assertThat(writer.droppedFor(deleted)).isZero();
    }
}
