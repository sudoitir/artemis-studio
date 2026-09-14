package io.github.sudoitir.artemisstudio.feature.sql;

import io.github.sudoitir.artemisstudio.feature.sql.internal.persistence.MessageCaptureNodeEntity;
import io.github.sudoitir.artemisstudio.feature.sql.internal.persistence.MessageCaptureNodeRepository;
import io.github.sudoitir.artemisstudio.feature.sql.internal.persistence.MessageIndexSubscriptionEntity;
import io.github.sudoitir.artemisstudio.feature.sql.internal.persistence.MessageIndexSubscriptionRepository;
import io.github.sudoitir.artemisstudio.platform.scrape.QueueSnapshot;
import io.github.sudoitir.artemisstudio.platform.scrape.QueueSnapshots;
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
    private final QueueSnapshots snapshots;
    private final JdbcTemplate jdbc;
    private final CaptureConsumer consumers;

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
        CaptureConsumer.Shortfall shortfall = consumers.takeShortfall(node.getNodeId(), subscription.getId());
        node.setHeldBytes(heldBytes(clusterId, subscription, node.getNodeId()));
        node.setUpdatedAt(Instant.now());
        captureNodes.save(node);
        String key = subscription.getId() + "|" + node.getNodeId();
        Mark previous = marks.put(key, new Mark(enqueued, captured));
        long lost = previous == null
                // The first pass has no interval to difference; it establishes the marks.
                ? 0
                : Math.max(0, enqueued - previous.enqueued()) - Math.max(0, captured - previous.captured());
        if (lost <= NOISE_FLOOR && shortfall.storeFailure() == null) {
            return;
        }
        if (lost > NOISE_FLOOR) {
            node.setDroppedEstimate(node.getDroppedEstimate() + lost);
        }
        // Losing messages is not a failure of the tap — it is installed and draining —
        // so the state is DEGRADED rather than FAILED, and it says how much and why.
        if (node.getCaptureState() == CaptureState.ACTIVE) {
            node.setCaptureState(CaptureState.DEGRADED);
        }
        node.setCaptureDetail(lossDetail(lost > NOISE_FLOOR ? lost : 0, shortfall));
        node.setUpdatedAt(Instant.now());
        captureNodes.save(node);
    }

    /** The loss in words, with the causes the drains actually recorded rather than a list of possibilities. */
    static String lossDetail(long lost, CaptureConsumer.Shortfall shortfall) {
        StringBuilder detail = new StringBuilder();
        if (lost > 0) {
            detail.append("About ")
                    .append(lost)
                    .append(" message")
                    .append(lost == 1 ? "" : "s")
                    .append(" enqueued on this node since the last pass were not captured.");
        }
        if (shortfall.storeFailure() != null) {
            detail.append(detail.isEmpty() ? "" : " ")
                    .append("Studio could not store captured messages (")
                    .append(shortfall.storeFailure())
                    .append("), so capture paused without acknowledging them and is retrying; the capture queue"
                            + " holds the backlog up to its bound and drops the oldest beyond it.");
        }
        if (shortfall.rateLimited() > 0) {
            detail.append(" ")
                    .append(shortfall.rateLimited())
                    .append(" were over the subscription's ingest rate cap; raise the cap or narrow the filter.");
        }
        if (shortfall.unreadable() > 0) {
            detail.append(" ")
                    .append(shortfall.unreadable())
                    .append(" could not be read after repeated attempts and were counted as lost.");
        }
        if (lost > 0
                && shortfall.storeFailure() == null
                && shortfall.rateLimited() == 0
                && shortfall.unreadable() == 0) {
            detail.append(" The capture queue drops the oldest when Studio cannot keep up; raise the ring size or"
                    + " narrow the capture filter.");
        }
        return detail.toString().trim();
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
        return snapshots.forCluster(clusterId).stream()
                .map(QueueSnapshot::queueName)
                .distinct()
                .filter(queue -> QueueNamePattern.matches(subscription.getQueuePattern(), queue))
                .toList();
    }

    private long enqueuedOnSource(UUID clusterId, MessageIndexSubscriptionEntity subscription, UUID nodeId) {
        return snapshots.forCluster(clusterId).stream()
                .filter(row -> nodeId.equals(row.nodeId()))
                .filter(row -> QueueNamePattern.matches(subscription.getQueuePattern(), row.queueName()))
                .mapToLong(QueueSnapshot::messagesAdded)
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
