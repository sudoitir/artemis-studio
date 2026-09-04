package io.github.sudoitir.artemisstudio.persist;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.broker.core.BrokerEvent;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@link BrokerEventWriter}: {@code accept} never blocks, overflow is counted per
 * cluster, and {@code flush} persists the buffer in one batch.
 */
class BrokerEventWriterTest extends PostgresIntegrationTest {

    @Autowired
    BrokerEventWriter writer;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    private final UUID clusterId = UUID.randomUUID();

    @BeforeEach
    void seedCluster() {
        jdbc.update(
                "INSERT INTO cluster (id, name) VALUES (:id, :name)",
                Map.of("id", clusterId, "name", "writer-" + clusterId));
        writer.setCapacity(10_000);
        writer.flush(); // drain anything a sibling test left buffered
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM cluster WHERE id = :id", Map.of("id", clusterId));
        writer.setCapacity(10_000);
    }

    private BrokerEvent event(String type) {
        return new BrokerEvent(
                clusterId,
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
    void overflowIsDroppedAndCountedNotBlocked() {
        writer.setCapacity(1);
        writer.accept(event("CONSUMER_CREATED")); // fills the soft cap
        writer.accept(event("CONSUMER_CLOSED")); // dropped
        writer.accept(event("SESSION_CREATED")); // dropped

        assertThat(writer.droppedFor(clusterId)).isEqualTo(2);

        writer.flush();
        assertThat(persisted()).isEqualTo(1);
    }
}
