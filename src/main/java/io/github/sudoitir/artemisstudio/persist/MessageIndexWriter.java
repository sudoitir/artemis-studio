package io.github.sudoitir.artemisstudio.persist;

import io.github.sudoitir.artemisstudio.sql.QueryResult.Row;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes an observation into {@code message_index} (ADR-0059).
 *
 * <p>An update before an insert, rather than an upsert. The table is partitioned on
 * {@code observed_at}, which is therefore part of its primary key, so a second
 * observation of the same message carries a different key and {@code ON CONFLICT}
 * would never match it. Identity across observations is
 * {@code (node, queue, messageId)} — a prefix of the primary key, so the update finds
 * its row on the index that already exists.
 *
 * <p>What {@code last_seen_at} can honestly say is bounded by how the tail reads: it
 * advances only while a message is still returned by a poll, and a poll asks the
 * broker only for what is at or after the high-water mark. A message that sits on a
 * queue while newer ones arrive stops being re-read, so its {@code last_seen_at}
 * stops advancing — the alternative is re-reading whole queues every few seconds,
 * which is the one thing Studio must not do. "Is it still there?" is answered
 * exactly, on demand, by verifying the row against the live broker.
 */
@Component
@RequiredArgsConstructor
public class MessageIndexWriter {

    /**
     * How far back a captured insert looks for the same message before writing it.
     *
     * <p>A capture consumer acknowledges in batches, so a crash mid-batch redelivers
     * what it had already written. The table is partitioned on {@code observed_at},
     * which Postgres requires in any unique index on it, so no unique constraint can
     * express "this message, once, ever" — the guard is a bounded existence check
     * instead. It serves from {@code ix_message_index_lookup}, and the window is what
     * keeps it a short index scan rather than a search of every partition. A
     * redelivery later than this would be written twice; a batch is acknowledged
     * within seconds, so the window is four orders of magnitude wider than the case
     * it covers.
     */
    private static final java.time.Duration REDELIVERY_WINDOW = java.time.Duration.ofMinutes(10);

    private final JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    /** Record one observation. Returns true when it was a message the index had not held. */
    public boolean observe(UUID clusterId, Row row, Instant at) {
        int updated = jdbc.update("""
                UPDATE message_index SET last_seen_at = ?
                 WHERE cluster_id = ? AND node_id = ? AND queue_name = ? AND message_id = ?
                """, Timestamp.from(at), clusterId, row.nodeId(), row.queueName(), row.messageId());
        if (updated > 0) {
            return false;
        }
        jdbc.update(
                """
                INSERT INTO message_index (
                    observed_at, last_seen_at, message_id, timestamp_ms, expiration_ms, size_bytes,
                    priority, message_type, queue_name, address, node_name, correlation_id, group_id,
                    user_id, reply_to, jms_type, body, props, cluster_id, node_id, durable)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """,
                Timestamp.from(at),
                Timestamp.from(at),
                row.messageId(),
                row.timestamp(),
                row.expiration(),
                row.size(),
                row.priority(),
                row.messageType(),
                row.queueName(),
                row.address(),
                row.nodeName(),
                row.correlationId(),
                row.groupId(),
                row.userId(),
                row.replyTo(),
                row.jmsType(),
                row.body(),
                properties(row),
                clusterId,
                row.nodeId(),
                row.durable());
        return true;
    }

    /**
     * Record a captured message (ADR-0062). Unlike {@link #observe}, this is an insert
     * and only an insert: a divert delivers each copy once, so there is no second
     * observation of the same message to fold into, and {@code last_seen_at} is the
     * moment it was drained rather than the last poll that still saw it.
     *
     * <p>{@code origAddress} and {@code sourceMessageId} come from the broker's
     * {@code _AMQ_ORIG_*} headers and are both nullable. The row's own
     * {@code queue_name} and {@code address} are Studio's answer, resolved from which
     * capture queue the message was drained from, so a query by the original queue
     * name works whether or not the broker copied a header (D2). Where the two
     * disagree the disagreement is visible rather than resolved silently.
     */
    public void captured(UUID clusterId, Row row, String origAddress, Long sourceMessageId, Instant at) {
        capturedBatch(List.of(new Captured(clusterId, row, origAddress, sourceMessageId, at)));
    }

    /** One captured message and the identity Studio resolved for it. */
    public record Captured(UUID clusterId, Row row, String origAddress, Long sourceMessageId, Instant at) {}

    /**
     * Write a batch of captured messages in one round trip (ADR-0062 D7).
     *
     * <p>This is not an optimisation to reach for later. A range-partitioned table with
     * three GIN indexes will not absorb single-row inserts at capture rate, and a write
     * path that cannot keep up turns "drop oldest, report the number" into "drop
     * everything, report a large number" — the ring fills because Studio is not
     * draining, not because the broker is busy.
     */
    public void capturedBatch(List<Captured> batch) {
        if (batch.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(CAPTURED_INSERT, batch, batch.size(), (statement, captured) -> {
            Row row = captured.row();
            Timestamp at = Timestamp.from(captured.at());
            int i = 0;
            statement.setTimestamp(++i, at);
            statement.setTimestamp(++i, at);
            statement.setLong(++i, row.messageId());
            statement.setLong(++i, row.timestamp());
            statement.setLong(++i, row.expiration());
            statement.setLong(++i, row.size());
            statement.setObject(++i, captured.sourceMessageId());
            statement.setInt(++i, row.priority());
            statement.setInt(++i, row.messageType());
            statement.setString(++i, row.queueName());
            statement.setString(++i, row.address());
            statement.setString(++i, row.nodeName());
            statement.setString(++i, row.correlationId());
            statement.setString(++i, row.groupId());
            statement.setString(++i, row.userId());
            statement.setString(++i, row.replyTo());
            statement.setString(++i, row.jmsType());
            statement.setString(++i, row.body());
            statement.setString(++i, properties(row));
            statement.setString(++i, captured.origAddress());
            statement.setObject(++i, captured.clusterId());
            statement.setObject(++i, row.nodeId());
            statement.setBoolean(++i, row.durable());
            statement.setBoolean(++i, row.bodyTruncated());
            statement.setObject(++i, captured.clusterId());
            statement.setString(++i, row.queueName());
            statement.setObject(++i, row.nodeId());
            statement.setLong(++i, row.messageId());
            statement.setTimestamp(++i, Timestamp.from(captured.at().minus(REDELIVERY_WINDOW)));
        });
    }

    private static final String CAPTURED_INSERT = """
            INSERT INTO message_index (
                observed_at, last_seen_at, message_id, timestamp_ms, expiration_ms, size_bytes,
                source_message_id, priority, message_type, queue_name, address, node_name,
                correlation_id, group_id, user_id, reply_to, jms_type, body, props, origin,
                orig_address, cluster_id, node_id, durable, body_truncated)
            SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, 'CAPTURED',
                   ?, ?, ?, ?, ?
             WHERE NOT EXISTS (
                   SELECT 1 FROM message_index
                    WHERE cluster_id = ? AND queue_name = ? AND node_id = ? AND message_id = ?
                      AND observed_at > ? )
            """;

    /**
     * Properties as JSON text, cast to {@code jsonb} by the statement. A plain string
     * bind plus a cast keeps this off the driver's own type classes, which are a
     * runtime dependency rather than a compile-time one.
     */
    private String properties(Row row) {
        return json.writeValueAsString(row.properties() == null ? java.util.Map.of() : row.properties());
    }
}
