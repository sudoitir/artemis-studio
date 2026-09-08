package io.github.sudoitir.artemisstudio.sql;

import io.github.sudoitir.artemisstudio.persist.AuditEventEntity;
import io.github.sudoitir.artemisstudio.persist.AuditService;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeRepository;
import io.github.sudoitir.artemisstudio.persist.CaptureMode;
import io.github.sudoitir.artemisstudio.persist.MessageCaptureNodeEntity;
import io.github.sudoitir.artemisstudio.persist.MessageCaptureNodeRepository;
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
    private final MessageCaptureNodeRepository captureNodes;
    private final BrokerNodeRepository nodes;
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

    /**
     * @param notCapturing why this subscription is recording nothing, or {@code null}
     *     when it is running. An empty index and a broken subscription look identical
     *     from a query, so the reason travels with the subscription rather than being
     *     left in a log.
     */
    public record Subscription(
            MessageIndexSubscriptionEntity entity, Footprint footprint, String notCapturing, List<CaptureNode> nodes) {}

    /** Per-node capture state with the node's name resolved, which the state row does not carry. */
    public record CaptureNode(MessageCaptureNodeEntity state, String nodeName) {}

    private Subscription describe(MessageIndexSubscriptionEntity entity, Footprint footprint) {
        // A sampled subscription has no per-node capture state, and reporting an empty
        // list for it is the honest answer rather than a missing one.
        List<CaptureNode> captureState = entity.getMode() == CaptureMode.CAPTURE
                ? captureNodes.findBySubscriptionId(entity.getId()).stream()
                        .map(state -> new CaptureNode(
                                state,
                                nodes.findById(state.getNodeId())
                                        .map(BrokerNodeEntity::getName)
                                        .orElse(null)))
                        .toList()
                : List.of();
        return new Subscription(
                entity, footprint, capture.notCapturingReason(entity.getId()).orElse(null), captureState);
    }

    /** The bounds and mode a subscription is created or changed with. Null means "leave it". */
    public record Spec(
            String queuePattern,
            Long intervalMs,
            Integer retentionDays,
            Boolean enabled,
            CaptureMode mode,
            Long ringSize,
            String filterString,
            Long maxBytes,
            Integer maxRate,
            Integer bodyCapBytes) {}

    @Transactional(readOnly = true)
    public List<Subscription> list(UUID clusterId) {
        clusterAccess.requireCluster(clusterId, Permissions.MESSAGE_READ);
        return subscriptions.findByClusterId(clusterId).stream()
                .map(s -> describe(s, footprint(s)))
                .toList();
    }

    @Transactional
    public Subscription create(UUID clusterId, Spec spec) {
        CaptureMode mode = spec.mode() == null ? CaptureMode.SAMPLE : spec.mode();
        clusterAccess.requireCluster(clusterId, permissionFor(mode));
        String pattern = validPattern(spec.queuePattern());

        MessageIndexSubscriptionEntity entity = new MessageIndexSubscriptionEntity();
        entity.setId(UUID.randomUUID());
        entity.setClusterId(clusterId);
        entity.setQueuePattern(pattern);
        entity.setIntervalMs(Math.max(1000, spec.intervalMs() == null ? 5000L : spec.intervalMs()));
        entity.setRetentionDays(Math.clamp(spec.retentionDays() == null ? 7 : spec.retentionDays(), 1, 90));
        entity.setCaptureFrom(Instant.now());
        entity.setCreatedAt(Instant.now());
        entity.setCreatedBy(actorName());
        entity.setEnabled(true);
        entity.setMode(mode);
        applyBounds(entity, spec);

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
                        "mode",
                        mode.name(),
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
            converge(saved);
            return describe(saved, new Footprint(0, 0, null));
        } catch (RuntimeException e) {
            audit.fail(event, e.getMessage());
            throw e;
        }
    }

    @Transactional
    public Subscription update(UUID clusterId, UUID id, Spec spec) {
        MessageIndexSubscriptionEntity entity = subscriptions
                .findById(id)
                .filter(sub -> sub.getClusterId().equals(clusterId))
                .orElseThrow(() -> new NotFoundException("Index subscription", id));
        CaptureMode target = spec.mode() == null ? entity.getMode() : spec.mode();
        // Gated on what the subscription will be as well as what it is: turning capture
        // on is the act that needs the authority, and turning it off needs it too —
        // stopping capture stops recording payload someone is relying on.
        clusterAccess.requireCluster(
                clusterId,
                target == CaptureMode.CAPTURE || entity.getMode() == CaptureMode.CAPTURE
                        ? Permissions.CAPTURE_WRITE
                        : Permissions.SETTINGS_WRITE);

        AuditEventEntity event = audit.begin(
                actorResolver.resolve(),
                "sql.index.update",
                "CLUSTER",
                entity.getQueuePattern(),
                clusterId,
                null,
                Map.of(
                        "enabled", String.valueOf(spec.enabled()),
                        "mode", String.valueOf(target),
                        "intervalMs", String.valueOf(spec.intervalMs()),
                        "retentionDays", String.valueOf(spec.retentionDays())),
                false);
        try {
            if (spec.enabled() != null) {
                entity.setEnabled(spec.enabled());
            }
            if (spec.intervalMs() != null) {
                entity.setIntervalMs(Math.max(1000, spec.intervalMs()));
            }
            if (spec.retentionDays() != null) {
                entity.setRetentionDays(Math.clamp(spec.retentionDays(), 1, 90));
            }
            entity.setMode(target);
            applyBounds(entity, spec);
            MessageIndexSubscriptionEntity saved = subscriptions.save(entity);
            audit.succeed(event, 1);
            // A disabled subscription stops capturing at once rather than at the next
            // reconcile: an operator who turns capture off has usually just decided
            // that this payload should not be stored.
            capture.stop(id);
            converge(saved);
            return describe(saved, footprint(saved));
        } catch (RuntimeException e) {
            audit.fail(event, e.getMessage());
            throw e;
        }
    }

    /** What a deletion destroyed, and whether it left broker objects to be swept. */
    public record Deleted(long messagesDestroyed, boolean hadCapture) {}

    /** Deletes the subscription and every message it captured. Returns how many were destroyed. */
    @Transactional
    public Deleted delete(UUID clusterId, UUID id) {
        MessageIndexSubscriptionEntity entity = require(clusterId, id);
        clusterAccess.requireCluster(clusterId, permissionFor(entity.getMode()));
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
            // The taps this subscription owned are now orphans. The reconciler is the
            // only thing that removes one (ADR-0062 D4), and the caller sweeps once
            // this transaction has committed — deliberately not from in here, which
            // would make broker calls inside the transaction that deleted the rows.
            return new Deleted(destroyed, entity.getMode() == CaptureMode.CAPTURE);
        } catch (RuntimeException e) {
            audit.fail(event, e.getMessage());
            throw e;
        }
    }

    // ---- internals ------------------------------------------------------

    private static String permissionFor(CaptureMode mode) {
        return mode == CaptureMode.CAPTURE ? Permissions.CAPTURE_WRITE : Permissions.SETTINGS_WRITE;
    }

    /**
     * Bounds are clamped rather than rejected. Every one of them exists to keep a
     * runaway subscription from becoming an incident, so a value outside the range is
     * a value someone typed, not a reason to refuse the whole change. An unset bound
     * keeps whatever the entity already carries, which for a new subscription is the
     * field's own default — the defaults live on the entity so that a subscription
     * created any other way is bounded too.
     */
    private static void applyBounds(MessageIndexSubscriptionEntity entity, Spec spec) {
        if (spec.ringSize() != null) {
            entity.setRingSize(Math.clamp(spec.ringSize(), 100L, 10_000_000L));
        }
        if (spec.maxBytes() != null) {
            entity.setMaxBytes(Math.max(1L, spec.maxBytes()));
        }
        if (spec.maxRate() != null) {
            entity.setMaxRate(Math.clamp(spec.maxRate(), 1, 1_000_000));
        }
        if (spec.bodyCapBytes() != null) {
            entity.setBodyCapBytes(Math.clamp(spec.bodyCapBytes(), 1024, 16 * 1024 * 1024));
        }
        if (spec.filterString() != null) {
            entity.setFilterString(spec.filterString().isBlank() ? null : spec.filterString());
        }
    }

    /**
     * Make the world match the subscription that was just written.
     *
     * <p>Only the sampled path converges inline. A capture pass makes management calls
     * to every live node, and this method runs inside the transaction that wrote the
     * subscription — holding a database connection open across broker HTTP is the
     * shape of an outage, not of a fast response. The reconciler runs on its own
     * schedule and is idempotent, so the tap is installed, or removed, on the next
     * pass; the screen says exactly that rather than implying it already happened.
     */
    private void converge(MessageIndexSubscriptionEntity entity) {
        if (entity.getMode() != CaptureMode.CAPTURE) {
            capture.reconcile();
        }
    }

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
