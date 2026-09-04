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

/** {@link RrFlowReaper} against a real Postgres: rows past the retention window go, recent ones stay. */
class RrFlowReaperTest extends PostgresIntegrationTest {

    @Autowired
    RrFlowReaper reaper;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    private final UUID clusterId = UUID.randomUUID();

    @BeforeEach
    void seedCluster() {
        jdbc.update(
                "INSERT INTO cluster (id, name) VALUES (:id, :name)",
                Map.of("id", clusterId, "name", "rr-reaper-" + clusterId));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM cluster WHERE id = :id", Map.of("id", clusterId));
    }

    private void flow(int ageDays) {
        jdbc.update("""
                INSERT INTO rr_flow (requested_at, request_address, reply_kind, state, cluster_id)
                VALUES (now() - make_interval(days => :age), 'rr.reaper', 'SHARED_QUEUE', 'COMPLETED', :c)
                """, Map.of("age", ageDays, "c", clusterId));
    }

    private int remaining() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM rr_flow WHERE cluster_id = :c", Map.of("c", clusterId), Integer.class);
    }

    @Test
    void reapDeletesRowsPastTheDefaultRetentionAndKeepsRecentOnes() {
        flow(30);
        flow(8);
        flow(1);
        assertThat(remaining()).isEqualTo(3);

        reaper.reap();

        assertThat(remaining()).isEqualTo(1);
    }
}
