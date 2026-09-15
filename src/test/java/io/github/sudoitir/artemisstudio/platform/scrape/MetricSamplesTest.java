package io.github.sudoitir.artemisstudio.platform.scrape;

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
 * {@link MetricSamples} against a real Postgres — exercises the
 * {@code date_bin} queries with real {@code java.time.Instant} bind parameters
 * (a Mockito-based test never runs the SQL, and pgjdbc cannot infer a type for
 * a bare {@code Instant} without the {@code Timestamp} conversion this
 * verifies stays in place).
 */
class MetricSamplesTest extends PostgresIntegrationTest {

    @Autowired
    MetricSamples repository;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    private final UUID clusterId = UUID.randomUUID();
    private final UUID nodeId = UUID.randomUUID();

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM metric_sample WHERE cluster_id = :c", Map.of("c", clusterId));
    }

    private void sample(String metric, Instant ts, double value) {
        sample(metric, "Q", ts, value);
    }

    private void sample(String metric, String subjectName, Instant ts, double value) {
        sample(metric, subjectName, nodeId, ts, value);
    }

    private void sample(String metric, String subjectName, UUID node, Instant ts, double value) {
        jdbc.update(
                """
                INSERT INTO metric_sample (ts, value, subject_type, subject_name, metric, cluster_id, node_id)
                VALUES (:ts, :value, 'QUEUE', :subject, :metric, :c, :n)
                """,
                Map.of(
                        "ts",
                        java.sql.Timestamp.from(ts),
                        "value",
                        value,
                        "subject",
                        subjectName,
                        "metric",
                        metric,
                        "c",
                        clusterId,
                        "n",
                        node));
    }

    /** One queue on two nodes: 100 → 200 on this node, 90,000 → 90,050 on another, 10 s apart. */
    private void sampleOnTwoNodes(Instant base) {
        UUID otherNode = UUID.randomUUID();
        sample("messagesAdded", "orders", base, 100.0);
        sample("messagesAdded", "orders", base.plusSeconds(10), 200.0);
        sample("messagesAdded", "orders", otherNode, base, 90_000.0);
        sample("messagesAdded", "orders", otherNode, base.plusSeconds(10), 90_050.0);
    }

    @Test
    void latestRateBySubjectSumsPerNodeRatesSoAQueueOnTwoNodesDoesNotReadAsAHugeRate() {
        // Across nodes, max - min would be 89,950 messages: a false rate-threshold alert, and an ack
        // rate that hides a slow consumer.
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        sampleOnTwoNodes(base);

        Map<String, Double> rates =
                repository.latestRateBySubject(clusterId, "messagesAdded", base, base.plusSeconds(60));

        assertThat(rates.get("orders")).isEqualTo(15.0); // 10/s on one node + 5/s on the other
    }

    @Test
    void rateSeriesSumsPerNodeDeltasSoAQueueOnTwoNodesDoesNotSpike() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        sampleOnTwoNodes(base);

        List<MetricSamples.Bucket> buckets = repository.rateSeries(
                clusterId, "messagesAdded", "orders", base, base.plusSeconds(60), Duration.ofSeconds(60));

        assertThat(buckets).hasSize(1);
        assertThat(buckets.get(0).value()).isEqualTo(150.0 / 60.0); // (100 + 50) messages over the bucket
    }

    @Test
    void gaugeSeriesAveragesWithinABucketAndReportsThePeak() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        sample("messageCount", base, 10.0);
        sample("messageCount", base.plusSeconds(10), 20.0);

        List<MetricSamples.Bucket> buckets = repository.gaugeSeries(
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

        List<MetricSamples.Bucket> buckets = repository.rateSeries(
                clusterId, "messagesAdded", null, base, base.plusSeconds(60), Duration.ofSeconds(60));

        assertThat(buckets).hasSize(1);
        // (130 - 100) / 60s
        assertThat(buckets.get(0).value()).isEqualTo(30.0 / 60.0);
    }

    @Test
    void latestRateBySubjectNeverProducesANegativeRate() {
        // max(value) - min(value) is never negative by construction — the same
        // GREATEST(...,0) formula rateSeries uses — so a broker restart mid-window
        // (100 then 40) still yields a non-negative, if imprecise, rate rather than
        // a negative spike.
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        sample("messagesAdded", "orders", base, 100.0);
        sample("messagesAdded", "orders", base.plusSeconds(30), 40.0);

        Map<String, Double> rates =
                repository.latestRateBySubject(clusterId, "messagesAdded", base, base.plusSeconds(60));

        assertThat(rates.get("orders")).isNotNegative();
    }

    @Test
    void latestRateWithTimeDividesByTheSpanItCoversAndReportsItsAge() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        sample("messagesAdded", "orders", base.plusSeconds(10), 100.0);
        sample("messagesAdded", "orders", base.plusSeconds(310), 400.0);
        sample("messagesAdded", "fresh-queue", base, 5.0);

        Map<String, MetricSamples.SubjectRate> rates =
                repository.latestRateWithTimeBySubject(clusterId, "messagesAdded", base, base.plusSeconds(900));

        assertThat(rates).doesNotContainKey("fresh-queue");
        MetricSamples.SubjectRate orders = rates.get("orders");
        assertThat(orders.rate()).isEqualTo(1.0); // 300 messages over the 300s the samples span
        assertThat(orders.asOf()).isEqualTo(base.plusSeconds(310));
        assertThat(orders.span()).isEqualTo(Duration.ofSeconds(300));
    }

    @Test
    void latestRateWithTimeSumsPerNodeRatesInsteadOfSubtractingOneNodesCounterFromAnothers() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        UUID otherNode = UUID.randomUUID();
        sample("messagesAdded", "orders", base, 100.0);
        sample("messagesAdded", "orders", base.plusSeconds(10), 200.0);
        jdbc.update(
                """
                INSERT INTO metric_sample (ts, value, subject_type, subject_name, metric, cluster_id, node_id)
                VALUES (:ts, :value, 'QUEUE', 'orders', 'messagesAdded', :c, :n)
                """, Map.of("ts", java.sql.Timestamp.from(base), "value", 90_000.0, "c", clusterId, "n", otherNode));
        jdbc.update(
                """
                INSERT INTO metric_sample (ts, value, subject_type, subject_name, metric, cluster_id, node_id)
                VALUES (:ts, :value, 'QUEUE', 'orders', 'messagesAdded', :c, :n)
                """,
                Map.of(
                        "ts",
                        java.sql.Timestamp.from(base.plusSeconds(10)),
                        "value",
                        90_050.0,
                        "c",
                        clusterId,
                        "n",
                        otherNode));

        MetricSamples.SubjectRate orders = repository
                .latestRateWithTimeBySubject(clusterId, "messagesAdded", base, base.plusSeconds(60))
                .get("orders");

        assertThat(orders.rate()).isEqualTo(15.0); // 10/s on one node + 5/s on the other
    }

    @Test
    void latestRateBySubjectOmitsAnUnderSampledSubject() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        sample("messagesAdded", "fresh-queue", base, 5.0); // only one sample in the window

        Map<String, Double> rates =
                repository.latestRateBySubject(clusterId, "messagesAdded", base, base.plusSeconds(60));

        assertThat(rates).doesNotContainKey("fresh-queue");
    }
}
