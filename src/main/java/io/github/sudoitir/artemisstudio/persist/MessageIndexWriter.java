package io.github.sudoitir.artemisstudio.persist;

import io.github.sudoitir.artemisstudio.sql.QueryResult.Row;
import java.sql.Timestamp;
import java.time.Instant;
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
     * Properties as JSON text, cast to {@code jsonb} by the statement. A plain string
     * bind plus a cast keeps this off the driver's own type classes, which are a
     * runtime dependency rather than a compile-time one.
     */
    private String properties(Row row) {
        return json.writeValueAsString(row.properties() == null ? java.util.Map.of() : row.properties());
    }
}
