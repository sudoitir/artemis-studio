package io.github.sudoitir.artemisstudio.persist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Read-only JPA view of {@code queue_snapshot} (changeset 005). Writes go through
 * {@link QueueSnapshotUpsert} (JDBC {@code INSERT … ON CONFLICT}, ADR-0016) —
 * this is a disposable cache with no stable row identity, so the estate-table
 * dirty-checking rule (ADR-0011) does not apply. Never {@code save()} this entity.
 */
@Entity
@Table(name = "queue_snapshot")
@IdClass(QueueSnapshotEntity.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QueueSnapshotEntity {

    @Id
    @Column(name = "node_id", nullable = false, updatable = false)
    private UUID nodeId;

    @Id
    @Column(name = "queue_name", nullable = false, updatable = false)
    private String queueName;

    @Column(name = "cluster_id", nullable = false)
    private UUID clusterId;

    @Column(name = "address", nullable = false)
    private String address;

    @Column(name = "routing_type", nullable = false)
    private String routingType;

    @Column(name = "durable", nullable = false)
    private boolean durable;

    /** The broker's own pause flag. A paused queue is correctly slow, and expected. */
    @Column(name = "paused", nullable = false)
    private boolean paused;

    @Column(name = "ts", nullable = false)
    private Instant ts;

    @Column(name = "message_count", nullable = false)
    private long messageCount;

    @Column(name = "consumer_count", nullable = false)
    private long consumerCount;

    @Column(name = "delivering_count", nullable = false)
    private long deliveringCount;

    @Column(name = "scheduled_count", nullable = false)
    private long scheduledCount;

    @Column(name = "messages_added", nullable = false)
    private long messagesAdded;

    @Column(name = "messages_acked", nullable = false)
    private long messagesAcked;

    @Column(name = "messages_expired", nullable = false)
    private long messagesExpired;

    /** Composite PK {@code (node_id, queue_name)} — matches the changeset-005 constraint. */
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private UUID nodeId;
        private String queueName;

        public Key() {}

        public Key(UUID nodeId, String queueName) {
            this.nodeId = nodeId;
            this.queueName = queueName;
        }
    }
}
