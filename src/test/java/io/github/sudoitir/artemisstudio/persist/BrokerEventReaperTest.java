package io.github.sudoitir.artemisstudio.persist;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** {@link BrokerEventReaper} against a real Postgres: rows past the window go, recent rows stay. */
class BrokerEventReaperTest extends PostgresIntegrationTest {

    @Autowired
    BrokerEventReaper reaper;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    private final UUID clusterId = UUID.randomUUID();

    @BeforeEach
    void seedCluster() {
        jdbc.update(
                "INSERT INTO cluster (id, name) VALUES (:id, :name)",
                Map.of("id", clusterId, "name", "reaper-" + clusterId));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM cluster WHERE id = :id", Map.of("id", clusterId));
    }

    private void event(int ageHours) {
        jdbc.update("""
                INSERT INTO broker_event (occurred_at, received_at, type, cluster_id)
                VALUES (now(), now() - make_interval(hours => :age), 'CONSUMER_CREATED', :c)
                """, Map.of("age", ageHours, "c", clusterId));
    }

    private int remaining() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM broker_event WHERE cluster_id = :c", Map.of("c", clusterId), Integer.class);
    }

    @Test
    void reapDeletesRowsPastTheWindowAndKeepsRecentOnes() {
        event(80);
        event(72);
        event(1);
        assertThat(remaining()).isEqualTo(3);

        reaper.setRetentionHours(71);
        reaper.reap();

        assertThat(remaining()).isEqualTo(1);
    }
}
