package io.github.sudoitir.artemisstudio.broker.capture;

import io.github.sudoitir.artemisstudio.persist.CaptureMode;
import io.github.sudoitir.artemisstudio.persist.CaptureState;
import io.github.sudoitir.artemisstudio.persist.MessageCaptureNodeEntity;
import io.github.sudoitir.artemisstudio.persist.MessageCaptureNodeRepository;
import io.github.sudoitir.artemisstudio.persist.MessageIndexSubscriptionEntity;
import io.github.sudoitir.artemisstudio.persist.MessageIndexSubscriptionRepository;
import io.github.sudoitir.artemisstudio.persist.QueueSnapshotEntity;
import io.github.sudoitir.artemisstudio.persist.QueueSnapshotRepository;
import io.github.sudoitir.artemisstudio.sql.QueueNamePattern;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * What capture missed, per node (ADR-0062 D7).
 *
 * <p>The measurement is the same one a live tail already makes and reuses its
 * inputs: {@code MessagesAdded} on the source queues, from {@code queue_snapshot},
 * differenced between passes, minus the rows actually written. A positive remainder
 * is messages the ring dropped, the rate cap rejected, or that arrived while the tap
 * was not installed. There is deliberately no second gap metric.
 *
 * <p>It is an estimate, and the product says so. Both terms come from sampled
 * counters, and a subscription with a divert filter legitimately captures fewer
 * messages than the address enqueued. What it is reliable for is the thing that
 * matters: a subscription steadily losing messages stops being reported as healthy.
 */
@Component
@RequiredArgsConstructor
public class CaptureLoss {

    /** Below this, a difference is measurement noise rather than loss. */
    private static final long NOISE_FLOOR = 5;

    private final MessageIndexSubscriptionRepository subscriptions;
    private final MessageCaptureNodeRepository captureNodes;
    private final QueueSnapshotRepository snapshots;
    private final JdbcTemplate jdbc;

    /** {@code MessagesAdded} and rows written at the previous pass, per (subscription, node). */
    private final Map<String, Mark> marks = new ConcurrentHashMap<>();

    private record Mark(long enqueued, long captured) {}

    /** Called at the end of each reconcile pass. Reads Postgres only — no broker call. */
    @Transactional
    public void measure(UUID clusterId) {
        for (MessageIndexSubscriptionEntity subscription : subscriptions.findByClusterId(clusterId)) {
            if (subscription.getMode() != CaptureMode.CAPTURE || !subscription.isEnabled()) {
                continue;
            }
            List<MessageCaptureNodeEntity> nodes = captureNodes.findBySubscriptionId(subscription.getId());
            for (MessageCaptureNodeEntity node : nodes) {
                measureNode(clusterId, subscription, node);
            }
            enforceStoredSizeBound(subscription, nodes);
        }
    }

    private void measureNode(
            UUID clusterId, MessageIndexSubscriptionEntity subscription, MessageCaptureNodeEntity node) {
        long enqueued = enqueuedOnSource(clusterId, subscription, node.getNodeId());
        long captured = capturedRows(clusterId, subscription, node.getNodeId());
        node.setHeldBytes(heldBytes(clusterId, subscription, node.getNodeId()));
        node.setUpdatedAt(Instant.now());
        captureNodes.save(node);
        String key = subscription.getId() + "|" + node.getNodeId();
        Mark previous = marks.put(key, new Mark(enqueued, captured));
        if (previous == null) {
            // The first pass has no interval to difference; it establishes the marks.
            return;
        }
        long arrived = Math.max(0, enqueued - previous.enqueued());
        long recorded = Math.max(0, captured - previous.captured());
        long lost = arrived - recorded;
        if (lost <= NOISE_FLOOR) {
            return;
        }
        node.setDroppedEstimate(node.getDroppedEstimate() + lost);
        // Losing messages is not a failure of the tap — it is installed and draining —
        // so the state is DEGRADED rather than FAILED, and it says how much.
        if (node.getCaptureState() == CaptureState.ACTIVE) {
            node.setCaptureState(CaptureState.DEGRADED);
        }
        node.setCaptureDetail("About " + lost + " message" + (lost == 1 ? "" : "s")
                + " enqueued on this node since the last pass were not captured. The ring drops the oldest"
                + " when Studio cannot keep up, and the ingest cap drops what is over rate; raise the ring"
                + " size or narrow the capture filter.");
        node.setUpdatedAt(Instant.now());
        captureNodes.save(node);
    }

    /**
     * The stored-size bound (ADR-0062 D7). Retention was sized for a sample; complete
     * capture at rate can reach terabytes long before the first partition ages out, so
     * a time bound alone is not a bound. Reaching it degrades the subscription and says
     * so — it does not silently stop recording, and it does not silently keep growing.
     */
    private void enforceStoredSizeBound(
            MessageIndexSubscriptionEntity subscription, List<MessageCaptureNodeEntity> nodes) {
        long held =
                nodes.stream().mapToLong(MessageCaptureNodeEntity::getHeldBytes).sum();
        if (held <= subscription.getMaxBytes()) {
            return;
        }
        for (MessageCaptureNodeEntity node : nodes) {
            if (node.getCaptureState() == CaptureState.ACTIVE) {
                node.setCaptureState(CaptureState.DEGRADED);
            }
            node.setCaptureDetail("This subscription holds about " + held + " bytes, over its "
                    + subscription.getMaxBytes() + "-byte bound. Retention will reclaim it as partitions age out;"
                    + " until then, shorten the retention, narrow the capture filter, or raise the bound.");
            node.setUpdatedAt(Instant.now());
            captureNodes.save(node);
        }
    }

    /** Payload bytes this node's capture holds, which is the half of the bound an operator decides about. */
    private long heldBytes(UUID clusterId, MessageIndexSubscriptionEntity subscription, UUID nodeId) {
        List<String> queues = capturedQueues(clusterId, subscription);
        if (queues.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(", ", java.util.Collections.nCopies(queues.size(), "?"));
        java.util.List<Object> binds = new java.util.ArrayList<>();
        binds.add(clusterId);
        binds.add(nodeId);
        binds.addAll(queues);
        Long bytes = jdbc.queryForObject(
                "SELECT coalesce(sum(coalesce(octet_length(body), 0) + pg_column_size(props)), 0)"
                        + " FROM message_index WHERE cluster_id = ? AND node_id = ? AND origin = 'CAPTURED'"
                        + " AND queue_name IN (" + placeholders + ')',
                Long.class,
                binds.toArray());
        return bytes == null ? 0 : bytes;
    }

    private List<String> capturedQueues(UUID clusterId, MessageIndexSubscriptionEntity subscription) {
        return snapshots.findByClusterId(clusterId).stream()
                .map(QueueSnapshotEntity::getQueueName)
                .distinct()
                .filter(queue -> QueueNamePattern.matches(subscription.getQueuePattern(), queue))
                .toList();
    }

    private long enqueuedOnSource(UUID clusterId, MessageIndexSubscriptionEntity subscription, UUID nodeId) {
        return snapshots.findByClusterId(clusterId).stream()
                .filter(row -> nodeId.equals(row.getNodeId()))
                .filter(row -> QueueNamePattern.matches(subscription.getQueuePattern(), row.getQueueName()))
                .mapToLong(QueueSnapshotEntity::getMessagesAdded)
                .sum();
    }

    private long capturedRows(UUID clusterId, MessageIndexSubscriptionEntity subscription, UUID nodeId) {
        List<String> queues = capturedQueues(clusterId, subscription);
        if (queues.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(", ", java.util.Collections.nCopies(queues.size(), "?"));
        java.util.List<Object> binds = new java.util.ArrayList<>();
        binds.add(clusterId);
        binds.add(nodeId);
        binds.addAll(queues);
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM message_index WHERE cluster_id = ? AND node_id = ? AND origin = 'CAPTURED'"
                        + " AND queue_name IN (" + placeholders + ')',
                Long.class,
                binds.toArray());
        return count == null ? 0 : count;
    }
}
