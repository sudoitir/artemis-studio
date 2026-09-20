package io.github.sudoitir.artemisstudio.platform.scrape;

import io.github.sudoitir.artemisstudio.platform.broker.QueueRow;
import java.sql.Timestamp;
import java.time.Instant;
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
 * Bulk writer for {@code queue_snapshot} (ADR-0016). A sweep rewrites thousands
 * of rows that have no identity worth dirty-checking, so this is a JDBC batched
 * {@code INSERT … ON CONFLICT (node_id, queue_name) DO UPDATE} rather than JPA.
 * {@code NamedParameterJdbcTemplate} + {@code ON CONFLICT} — both stock, no new
 * dependency. The estate tables stay pure JPA.
 */
@Component
@RequiredArgsConstructor
public class QueueSnapshotUpsert {

    private static final String UPSERT = """
            INSERT INTO queue_snapshot
              (ts, message_count, consumer_count, delivering_count, scheduled_count,
               messages_added, messages_acked, messages_expired,
               address, queue_name, routing_type, cluster_id, node_id, durable, paused)
            VALUES
              (now(), :messageCount, :consumerCount, :deliveringCount, :scheduledCount,
               :messagesAdded, :messagesAcked, :messagesExpired,
               :address, :queueName, :routingType, :clusterId, :nodeId, :durable, :paused)
            ON CONFLICT (node_id, queue_name) DO UPDATE SET
               ts               = now(),
               message_count    = EXCLUDED.message_count,
               consumer_count   = EXCLUDED.consumer_count,
               delivering_count = EXCLUDED.delivering_count,
               scheduled_count  = EXCLUDED.scheduled_count,
               messages_added   = EXCLUDED.messages_added,
               messages_acked   = EXCLUDED.messages_acked,
               messages_expired = EXCLUDED.messages_expired,
               address          = EXCLUDED.address,
               routing_type     = EXCLUDED.routing_type,
               cluster_id       = EXCLUDED.cluster_id,
               durable          = EXCLUDED.durable,
               paused           = EXCLUDED.paused
            """;

    private static final String REAP_STALE = "DELETE FROM queue_snapshot WHERE node_id = :nodeId AND ts < :sweepStart";

    private static final String FORGET_QUEUE =
            "DELETE FROM queue_snapshot WHERE cluster_id = :clusterId AND queue_name = :queueName";

    private final NamedParameterJdbcTemplate jdbc;

    /** Upsert a batch of rows in one round trip. */
    @Transactional
    public void upsertBatch(List<QueueRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        SqlParameterSource[] params =
                rows.stream().map(QueueSnapshotUpsert::params).toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate(UPSERT, params);
    }

    /**
     * Drop rows for a node that this sweep did not touch — a queue removed on the
     * broker since the sweep began. Only called when a full tier-C sweep of the
     * node completes, so a partial page never reaps live rows.
     */
    @Transactional
    public int reapStale(UUID nodeId, Instant sweepStart) {
        return jdbc.update(REAP_STALE, Map.of("nodeId", nodeId, "sweepStart", Timestamp.from(sweepStart)));
    }

    /**
     * Drop a queue's rows across the cluster, for a queue Studio has just destroyed.
     *
     * <p>The snapshot is a cache of what the brokers run, and a sweep reaps what is
     * gone — but a destroyed queue stays listed until that sweep comes round, so the
     * delete reads as though it did nothing, and acting on the row that is left fails
     * against an MBean that no longer exists. A command that destroyed the queue knows
     * it is gone; this is that knowledge, applied to the cache.
     */
    @Transactional
    public int forget(UUID clusterId, String queueName) {
        return jdbc.update(FORGET_QUEUE, Map.of("clusterId", clusterId, "queueName", queueName));
    }

    private static SqlParameterSource params(QueueRow r) {
        return new MapSqlParameterSource()
                .addValue("messageCount", r.messageCount())
                .addValue("consumerCount", r.consumerCount())
                .addValue("deliveringCount", r.deliveringCount())
                .addValue("scheduledCount", r.scheduledCount())
                .addValue("messagesAdded", r.messagesAdded())
                .addValue("messagesAcked", r.messagesAcked())
                .addValue("messagesExpired", r.messagesExpired())
                .addValue("address", r.address())
                .addValue("queueName", r.queueName())
                .addValue("routingType", r.routingType())
                .addValue("clusterId", r.clusterId())
                .addValue("nodeId", r.nodeId())
                .addValue("durable", r.durable())
                .addValue("paused", r.paused());
    }
}
