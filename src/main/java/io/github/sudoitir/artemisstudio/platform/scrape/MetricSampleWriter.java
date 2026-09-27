package io.github.sudoitir.artemisstudio.platform.scrape;

import io.github.sudoitir.artemisstudio.platform.broker.QueueRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appends {@code metric_sample} rows for swept queues (ADR-0006 — Studio owns the
 * timeseries in Postgres). Six points per queue per tier-B/C tick for the counters
 * the queue grid, the charts and the consumer-health verdict care about. Append-only,
 * JDBC batch — the table is range-partitioned and insert-tuned (changeset 005).
 *
 * <p>{@code deliveringCount} and {@code messagesExpired} are sampled for ADR-0089:
 * in-flight depth is what separates a stalled consumer from a starved one, and both
 * arrive on the {@link QueueRow} the sweep has already fetched, so recording them
 * costs no additional broker request. {@code metric_sample.metric} is a {@code text}
 * column, so adding a name needs no migration.
 */
@Component
@RequiredArgsConstructor
public class MetricSampleWriter {

    private static final String INSERT = """
            INSERT INTO metric_sample (ts, value, subject_type, subject_name, metric, cluster_id, node_id)
            VALUES (now(), :value, 'QUEUE', :subjectName, :metric, :clusterId, :nodeId)
            """;

    private static final String PLUGIN_INSERT = """
            INSERT INTO metric_sample (ts, value, subject_type, subject_name, metric, cluster_id, node_id)
            VALUES (now(), :value, 'PLUGIN', :subjectName, :metric, :clusterId, NULL)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    @Transactional
    public void appendQueueSamples(List<QueueRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        List<SqlParameterSource> params = new ArrayList<>(rows.size() * 6);
        for (QueueRow r : rows) {
            params.add(sample(r, "messageCount", r.messageCount()));
            params.add(sample(r, "consumerCount", r.consumerCount()));
            params.add(sample(r, "messagesAdded", r.messagesAdded()));
            params.add(sample(r, "messagesAcked", r.messagesAcked()));
            params.add(sample(r, "deliveringCount", r.deliveringCount()));
            params.add(sample(r, "messagesExpired", r.messagesExpired()));
        }
        jdbc.batchUpdate(INSERT, params.toArray(SqlParameterSource[]::new));
    }

    /** A plugin metric's values on one cluster (ADR-0113): subject type {@code PLUGIN}, no node. */
    @Transactional
    public void appendPluginSamples(UUID clusterId, String metric, Map<String, Double> values) {
        if (values.isEmpty()) {
            return;
        }
        SqlParameterSource[] params = values.entrySet().stream()
                .map(e -> new MapSqlParameterSource()
                        .addValue("value", e.getValue())
                        .addValue("subjectName", e.getKey())
                        .addValue("metric", metric)
                        .addValue("clusterId", clusterId))
                .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate(PLUGIN_INSERT, params);
    }

    private static SqlParameterSource sample(QueueRow r, String metric, long value) {
        return new MapSqlParameterSource()
                .addValue("value", (double) value)
                .addValue("subjectName", r.queueName())
                .addValue("metric", metric)
                .addValue("clusterId", r.clusterId())
                .addValue("nodeId", r.nodeId());
    }
}
