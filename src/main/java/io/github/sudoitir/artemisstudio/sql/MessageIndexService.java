package io.github.sudoitir.artemisstudio.sql;

import io.github.sudoitir.artemisstudio.persist.AuditEventEntity;
import io.github.sudoitir.artemisstudio.persist.AuditService;
import io.github.sudoitir.artemisstudio.persist.MessageIndexSubscriptionEntity;
import io.github.sudoitir.artemisstudio.persist.MessageIndexSubscriptionRepository;
import io.github.sudoitir.artemisstudio.persist.QueueSnapshotEntity;
import io.github.sudoitir.artemisstudio.persist.QueueSnapshotRepository;
import io.github.sudoitir.artemisstudio.security.ActorResolver;
import io.github.sudoitir.artemisstudio.security.Permissions;
import io.github.sudoitir.artemisstudio.service.ClusterAccessGuard;
import io.github.sudoitir.artemisstudio.service.NotFoundException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Index subscriptions: the deliberate act of storing message payload (ADR-0059).
 *
 * <p>Creating one is a configuration change, not a query, so it takes the settings
 * write permission and is audited — including what it will keep and for how long.
 * Deleting one destroys everything it captured, so the count that will be destroyed
 * is available before the deletion is armed, and the deletion reports what it
 * actually removed.
 */
@Service
@RequiredArgsConstructor
public class MessageIndexService {

    private final MessageIndexSubscriptionRepository subscriptions;
    private final QueueSnapshotRepository snapshots;
    private final MessageIndexCapture capture;
    private final ClusterAccessGuard clusterAccess;
    private final ActorResolver actorResolver;
    private final AuditService audit;
    private final JdbcTemplate jdbc;

    /**
     * What a subscription is currently costing.
     *
     * @param messages rows the index holds for this subscription's queues
     * @param payloadBytes the bodies and properties held, which is the part an
     *     operator is deciding about; the table's own overhead is not included
     */
    public record Footprint(long messages, long payloadBytes, Instant oldest) {}

    public record Subscription(MessageIndexSubscriptionEntity entity, Footprint footprint) {}

    @Transactional(readOnly = true)
    public List<Subscription> list(UUID clusterId) {
        clusterAccess.requireCluster(clusterId, Permissions.MESSAGE_READ);
        return subscriptions.findByClusterId(clusterId).stream()
                .map(s -> new Subscription(s, footprint(s)))
                .toList();
    }

    @Transactional
    public Subscription create(UUID clusterId, String queuePattern, long intervalMs, int retentionDays) {
        clusterAccess.requireCluster(clusterId, Permissions.SETTINGS_WRITE);
        String pattern = validPattern(queuePattern);

        MessageIndexSubscriptionEntity entity = new MessageIndexSubscriptionEntity();
        entity.setId(UUID.randomUUID());
        entity.setClusterId(clusterId);
        entity.setQueuePattern(pattern);
        entity.setIntervalMs(Math.max(1000, intervalMs));
        entity.setRetentionDays(Math.clamp(retentionDays, 1, 90));
        entity.setCaptureFrom(Instant.now());
        entity.setCreatedAt(Instant.now());
        entity.setCreatedBy(actorName());
        entity.setEnabled(true);

        AuditEventEntity event = audit.begin(
                actorResolver.resolve(),
                "sql.index.create",
                "CLUSTER",
                pattern,
                clusterId,
                null,
                Map.of(
                        "queuePattern",
                        pattern,
                        "intervalMs",
                        entity.getIntervalMs(),
                        "retentionDays",
                        entity.getRetentionDays(),
                        "storesMessageBodies",
                        true),
                false);
        try {
            MessageIndexSubscriptionEntity saved = subscriptions.save(entity);
            audit.succeed(event, 1);
            capture.reconcile();
            return new Subscription(saved, new Footprint(0, 0, null));
        } catch (RuntimeException e) {
            audit.fail(event, e.getMessage());
            throw e;
        }
    }

    @Transactional
    public Subscription update(UUID clusterId, UUID id, Boolean enabled, Long intervalMs, Integer retentionDays) {
        clusterAccess.requireCluster(clusterId, Permissions.SETTINGS_WRITE);
        MessageIndexSubscriptionEntity entity = require(clusterId, id);

        AuditEventEntity event = audit.begin(
                actorResolver.resolve(),
                "sql.index.update",
                "CLUSTER",
                entity.getQueuePattern(),
                clusterId,
                null,
                Map.of(
                        "enabled", String.valueOf(enabled),
                        "intervalMs", String.valueOf(intervalMs),
                        "retentionDays", String.valueOf(retentionDays)),
                false);
        try {
            if (enabled != null) {
                entity.setEnabled(enabled);
            }
            if (intervalMs != null) {
                entity.setIntervalMs(Math.max(1000, intervalMs));
            }
            if (retentionDays != null) {
                entity.setRetentionDays(Math.clamp(retentionDays, 1, 90));
            }
            MessageIndexSubscriptionEntity saved = subscriptions.save(entity);
            audit.succeed(event, 1);
            // A disabled subscription stops capturing at once rather than at the next
            // reconcile: an operator who turns capture off has usually just decided
            // that this payload should not be stored.
            capture.stop(id);
            capture.reconcile();
            return new Subscription(saved, footprint(saved));
        } catch (RuntimeException e) {
            audit.fail(event, e.getMessage());
            throw e;
        }
    }

    /** Deletes the subscription and every message it captured. Returns how many were destroyed. */
    @Transactional
    public long delete(UUID clusterId, UUID id) {
        clusterAccess.requireCluster(clusterId, Permissions.SETTINGS_WRITE);
        MessageIndexSubscriptionEntity entity = require(clusterId, id);
        Footprint before = footprint(entity);

        AuditEventEntity event = audit.begin(
                actorResolver.resolve(),
                "sql.index.delete",
                "CLUSTER",
                entity.getQueuePattern(),
                clusterId,
                null,
                Map.of("queuePattern", entity.getQueuePattern(), "capturedMessages", before.messages()),
                false);
        try {
            capture.stop(id);
            long destroyed = deleteCaptured(entity);
            subscriptions.delete(entity);
            audit.succeed(event, destroyed);
            return destroyed;
        } catch (RuntimeException e) {
            audit.fail(event, e.getMessage());
            throw e;
        }
    }

    // ---- internals ------------------------------------------------------

    private MessageIndexSubscriptionEntity require(UUID clusterId, UUID id) {
        return subscriptions
                .findById(id)
                .filter(s -> s.getClusterId().equals(clusterId))
                .orElseThrow(() -> new NotFoundException("Index subscription", id));
    }

    /**
     * The queues this subscription's pattern currently claims. Rows are keyed by queue
     * name rather than by subscription id, because a message on a queue two
     * subscriptions match is one captured message, not two.
     */
    private List<String> queuesOf(MessageIndexSubscriptionEntity subscription) {
        return snapshots.findByClusterId(subscription.getClusterId()).stream()
                .map(QueueSnapshotEntity::getQueueName)
                .distinct()
                .filter(q -> QueueNamePattern.matches(subscription.getQueuePattern(), q))
                .toList();
    }

    private Footprint footprint(MessageIndexSubscriptionEntity subscription) {
        List<String> queues = queuesOf(subscription);
        if (queues.isEmpty()) {
            return new Footprint(0, 0, null);
        }
        List<Object> binds = new ArrayList<>();
        binds.add(subscription.getClusterId());
        binds.addAll(queues);
        String placeholders = String.join(", ", java.util.Collections.nCopies(queues.size(), "?"));
        return jdbc.queryForObject(
                """
                SELECT count(*) AS messages,
                       coalesce(sum(coalesce(octet_length(body), 0) + pg_column_size(props)), 0) AS payload_bytes,
                       min(observed_at) AS oldest
                  FROM message_index
                 WHERE cluster_id = ? AND queue_name IN (%s)
                """.formatted(placeholders),
                (rs, rowNum) -> new Footprint(
                        rs.getLong("messages"),
                        rs.getLong("payload_bytes"),
                        rs.getTimestamp("oldest") == null
                                ? null
                                : rs.getTimestamp("oldest").toInstant()),
                binds.toArray());
    }

    private long deleteCaptured(MessageIndexSubscriptionEntity subscription) {
        List<String> queues = queuesOf(subscription);
        if (queues.isEmpty()) {
            return 0;
        }
        // Everything the subscription could have captured, back to the start of its
        // retention window — bounded so the statement cannot run away on a partition
        // that a longer-lived subscription still owns.
        Instant floor = Instant.now().minus(Duration.ofDays(subscription.getRetentionDays() + 1L));
        List<Object> binds = new ArrayList<>();
        binds.add(subscription.getClusterId());
        binds.add(Timestamp.from(floor));
        binds.addAll(queues);
        String placeholders = String.join(", ", java.util.Collections.nCopies(queues.size(), "?"));
        return jdbc.update(
                "DELETE FROM message_index WHERE cluster_id = ? AND observed_at >= ? AND queue_name IN (" + placeholders
                        + ')',
                binds.toArray());
    }

    /**
     * The pattern goes into a quoted SQL identifier when capture plans its query, so a
     * quote in it would be a second place where operator text becomes SQL text. There
     * is no legal Artemis queue name containing one.
     */
    private String validPattern(String pattern) {
        String trimmed = pattern == null ? "" : pattern.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("A subscription needs a queue name or pattern.");
        }
        if (trimmed.indexOf('"') >= 0 || trimmed.indexOf('\'') >= 0) {
            throw new IllegalArgumentException("A queue pattern cannot contain a quote character.");
        }
        return trimmed;
    }

    private String actorName() {
        var actor = actorResolver.resolve();
        return actor == null ? null : actor.displayName();
    }
}
