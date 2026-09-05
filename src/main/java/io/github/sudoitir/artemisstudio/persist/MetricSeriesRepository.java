package io.github.sudoitir.artemisstudio.persist;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Bucketed reads over {@code metric_sample} (metrics spec, ADR-0033). JDBC, no JPA
 * entity — an analytic read of a partitioned, disposable cache, the same shape as
 * {@code queue_snapshot}'s bulk-upsert path (ADR-0016).
 *
 * <p>A gauge (point-in-time quantity, e.g. {@code messageCount}) is averaged per
 * bucket with its maximum reported as a peak. A counter (broker-lifetime monotonic,
 * e.g. {@code messagesAdded}) is converted to a per-second rate from the change in
 * value across the bucket, computed per subject first and summed — collapsing
 * multiple queues' independent counters into one cluster-wide max/min would produce
 * a meaningless number. {@code GREATEST(..., 0)} clamps a broker-restart counter
 * reset to zero rather than a negative spike.
 */
@Repository
@RequiredArgsConstructor
public class MetricSeriesRepository {

    public record Bucket(Instant ts, double value, Double peak) {}

    private final NamedParameterJdbcTemplate jdbc;

    public List<Bucket> gaugeSeries(
            UUID clusterId, String metric, String subjectName, Instant from, Instant to, Duration step) {
        String sql = """
                SELECT date_bin(make_interval(secs => :stepSeconds), ts, TIMESTAMPTZ '2000-01-01') AS bucket,
                       avg(value) AS v, max(value) AS peak
                  FROM metric_sample
                 WHERE cluster_id = :clusterId AND subject_type = 'QUEUE' AND metric = :metric
                   AND (:subjectName::text IS NULL OR subject_name = :subjectName)
                   AND ts >= :from AND ts < :to
                 GROUP BY bucket ORDER BY bucket
                """;
        return jdbc.query(
                sql,
                params(clusterId, metric, subjectName, from, to, step),
                (rs, i) -> new Bucket(
                        rs.getTimestamp("bucket").toInstant(), rs.getDouble("v"), (Double) rs.getObject("peak")));
    }

    public List<Bucket> rateSeries(
            UUID clusterId, String metric, String subjectName, Instant from, Instant to, Duration step) {
        String sql = """
                SELECT bucket, sum(delta) / :stepSeconds AS v
                  FROM (
                    SELECT date_bin(make_interval(secs => :stepSeconds), ts, TIMESTAMPTZ '2000-01-01') AS bucket,
                           subject_name,
                           GREATEST(max(value) - min(value), 0) AS delta
                      FROM metric_sample
                     WHERE cluster_id = :clusterId AND subject_type = 'QUEUE' AND metric = :metric
                       AND (:subjectName::text IS NULL OR subject_name = :subjectName)
                       AND ts >= :from AND ts < :to
                     GROUP BY bucket, subject_name
                  ) delta_per_subject
                 GROUP BY bucket ORDER BY bucket
                """;
        return jdbc.query(
                sql,
                params(clusterId, metric, subjectName, from, to, step),
                (rs, i) -> new Bucket(rs.getTimestamp("bucket").toInstant(), rs.getDouble("v"), null));
    }

    private MapSqlParameterSource params(
            UUID clusterId, String metric, String subjectName, Instant from, Instant to, Duration step) {
        // pgjdbc cannot infer a SQL type for a bare java.time.Instant parameter
        // ("Can't infer the SQL type to use..."); java.sql.Timestamp maps to
        // timestamptz without ambiguity.
        return new MapSqlParameterSource(Map.of(
                        "clusterId",
                        clusterId,
                        "metric",
                        metric,
                        "from",
                        Timestamp.from(from),
                        "to",
                        Timestamp.from(to)))
                .addValue("subjectName", subjectName)
                .addValue("stepSeconds", (double) step.toSeconds());
    }
}
