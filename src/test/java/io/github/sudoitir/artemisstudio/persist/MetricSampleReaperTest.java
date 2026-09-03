package io.github.sudoitir.artemisstudio.persist;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** {@link MetricSampleReaper} against a real Postgres: old rows go, recent rows stay. */
class MetricSampleReaperTest extends PostgresIntegrationTest {

    @Autowired
    MetricSampleReaper reaper;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    private final UUID clusterId = UUID.randomUUID();
    private final UUID nodeId = UUID.randomUUID();

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM metric_sample WHERE cluster_id = :c", Map.of("c", clusterId));
    }

    private void sample(String metric, int ageDays) {
        jdbc.update("""
                INSERT INTO metric_sample (ts, value, subject_type, subject_name, metric, cluster_id, node_id)
                VALUES (now() - make_interval(days => :age), 1.0, 'QUEUE', 'Q', :metric, :c, :n)
                """, Map.of("age", ageDays, "metric", metric, "c", clusterId, "n", nodeId));
    }

    private int remaining() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM metric_sample WHERE cluster_id = :c", Map.of("c", clusterId), Integer.class);
    }

    @Test
    void reapDeletesRowsPastTheRetentionWindowAndKeepsRecentOnes() {
        sample("old", 10);
        sample("edge", 8);
        sample("fresh", 1);
        assertThat(remaining()).isEqualTo(3);

        reaper.setRetentionDays(7);
        reaper.reap();

        assertThat(remaining()).isEqualTo(1);
    }
}
