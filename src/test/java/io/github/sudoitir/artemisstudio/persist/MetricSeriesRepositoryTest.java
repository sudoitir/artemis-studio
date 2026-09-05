package io.github.sudoitir.artemisstudio.persist;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@link MetricSeriesRepository} against a real Postgres — exercises the
 * {@code date_bin} queries with real {@code java.time.Instant} bind parameters
 * (a Mockito-based test never runs the SQL, and pgjdbc cannot infer a type for
 * a bare {@code Instant} without the {@code Timestamp} conversion this
 * verifies stays in place).
 */
class MetricSeriesRepositoryTest extends PostgresIntegrationTest {

    @Autowired
    MetricSeriesRepository repository;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    private final UUID clusterId = UUID.randomUUID();
    private final UUID nodeId = UUID.randomUUID();

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM metric_sample WHERE cluster_id = :c", Map.of("c", clusterId));
    }

    private void sample(String metric, Instant ts, double value) {
        jdbc.update(
                """
                INSERT INTO metric_sample (ts, value, subject_type, subject_name, metric, cluster_id, node_id)
                VALUES (:ts, :value, 'QUEUE', 'Q', :metric, :c, :n)
                """,
                Map.of(
                        "ts",
                        java.sql.Timestamp.from(ts),
                        "value",
                        value,
                        "metric",
                        metric,
                        "c",
                        clusterId,
                        "n",
                        nodeId));
    }

    @Test
    void gaugeSeriesAveragesWithinABucketAndReportsThePeak() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        sample("messageCount", base, 10.0);
        sample("messageCount", base.plusSeconds(10), 20.0);

        List<MetricSeriesRepository.Bucket> buckets = repository.gaugeSeries(
                clusterId, "messageCount", null, base, base.plusSeconds(60), Duration.ofSeconds(60));

        assertThat(buckets).hasSize(1);
        assertThat(buckets.get(0).value()).isEqualTo(15.0);
        assertThat(buckets.get(0).peak()).isEqualTo(20.0);
    }

    @Test
    void rateSeriesDerivesFromTheCounterDeltaAcrossTheBucket() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        sample("messagesAdded", base, 100.0);
        sample("messagesAdded", base.plusSeconds(30), 130.0);

        List<MetricSeriesRepository.Bucket> buckets = repository.rateSeries(
                clusterId, "messagesAdded", null, base, base.plusSeconds(60), Duration.ofSeconds(60));

        assertThat(buckets).hasSize(1);
        // (130 - 100) / 60s
        assertThat(buckets.get(0).value()).isEqualTo(30.0 / 60.0);
    }
}
