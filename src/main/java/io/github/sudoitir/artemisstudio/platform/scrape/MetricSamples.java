package io.github.sudoitir.artemisstudio.platform.scrape;

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
public class MetricSamples {

    public record Bucket(Instant ts, double value, Double peak) {}

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * A subject's counter rate between its oldest and newest sample in a window.
     *
     * @param asOf the newest sample's time, so a reader can state the rate's age
     * @param span the time between the two samples the rate spans — a slow-tier queue's
     *     rate is an average over minutes, and a reader must be able to say so
     */
    public record SubjectRate(double rate, Instant asOf, Duration span) {}

    /**
     * Like {@link #latestRateBySubject}, but computed per node and summed, divided by the
     * time each node's samples actually span rather than by the window, and carrying the
     * longest span and the newest sample's time. A subject with no node sampled twice in
     * the window is omitted, never zero.
     */
    public Map<String, SubjectRate> latestRateWithTimeBySubject(
            UUID clusterId, String metric, Instant from, Instant to) {
        // Per node first: each node's counter is its own lifetime count, so max - min across
        // nodes would subtract one broker's counter from another's.
        String sql = """
                SELECT subject_name,
                       sum(delta / span_seconds) AS rate,
                       max(as_of) AS as_of,
                       max(span_seconds) AS span_seconds
                  FROM (SELECT subject_name,
                               GREATEST(max(value) - min(value), 0)::double precision AS delta,
                               max(ts) AS as_of,
                               EXTRACT(EPOCH FROM max(ts) - min(ts))::double precision AS span_seconds
                          FROM metric_sample
                         WHERE cluster_id = :clusterId AND subject_type = 'QUEUE' AND metric = :metric
                           AND ts >= :from AND ts < :to
                         GROUP BY subject_name, node_id
                        HAVING count(*) >= 2 AND max(ts) > min(ts)) per_node
                 GROUP BY subject_name
                """;
        MapSqlParameterSource p = new MapSqlParameterSource(Map.of(
                "clusterId", clusterId, "metric", metric, "from", Timestamp.from(from), "to", Timestamp.from(to)));
        Map<String, SubjectRate> out = new java.util.HashMap<>();
        jdbc.query(sql, p, rs -> {
            double spanSeconds = rs.getDouble("span_seconds");
            out.put(
                    rs.getString("subject_name"),
                    new SubjectRate(
                            rs.getDouble("rate"),
                            rs.getTimestamp("as_of").toInstant(),
                            Duration.ofMillis(Math.round(spanSeconds * 1000))));
        });
        return out;
    }

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

    /**
     * The current rate per subject over one window (metrics spec) — used by
     * alert rate-threshold rules, one query per {@code (cluster, metric)} per
     * tick regardless of rule count, not one query per rule. Same restart-safe
     * {@code GREATEST(...,0)} clamp as {@link #rateSeries}, but grouped over one
     * window instead of {@code date_bin} buckets. A subject with fewer than two
     * samples in the window has no computable rate and is omitted, never
     * reported as zero — reporting zero would read as "throughput dropped" for
     * a queue simply not sampled twice yet.
     */
    public Map<String, Double> latestRateBySubject(UUID clusterId, String metric, Instant from, Instant to) {
        String sql = """
                SELECT subject_name, GREATEST(max(value) - min(value), 0) / :windowSeconds AS rate
                  FROM metric_sample
                 WHERE cluster_id = :clusterId AND subject_type = 'QUEUE' AND metric = :metric
                   AND ts >= :from AND ts < :to
                 GROUP BY subject_name
                HAVING count(*) >= 2
                """;
        double windowSeconds = Math.max(1, Duration.between(from, to).getSeconds());
        MapSqlParameterSource p = new MapSqlParameterSource(Map.of(
                        "clusterId",
                        clusterId,
                        "metric",
                        metric,
                        "from",
                        Timestamp.from(from),
                        "to",
                        Timestamp.from(to)))
                .addValue("windowSeconds", windowSeconds);
        Map<String, Double> out = new java.util.HashMap<>();
        jdbc.query(sql, p, rs -> {
            out.put(rs.getString("subject_name"), rs.getDouble("rate"));
        });
        return out;
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
