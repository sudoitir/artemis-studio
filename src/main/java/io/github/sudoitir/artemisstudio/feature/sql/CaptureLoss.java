package io.github.sudoitir.artemisstudio.feature.sql;

import io.github.sudoitir.artemisstudio.feature.sql.internal.persistence.MessageCaptureNodeEntity;
import io.github.sudoitir.artemisstudio.feature.sql.internal.persistence.MessageCaptureNodeRepository;
import io.github.sudoitir.artemisstudio.feature.sql.internal.persistence.MessageIndexSubscriptionEntity;
import io.github.sudoitir.artemisstudio.feature.sql.internal.persistence.MessageIndexSubscriptionRepository;
import io.github.sudoitir.artemisstudio.platform.scrape.QueueSnapshot;
import io.github.sudoitir.artemisstudio.platform.scrape.QueueSnapshots;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * What capture missed, per node (ADR-0062 D7).
 *
 * <p>The measurement is the change in what was routed to the covered addresses, from
 * {@code MessagesAdded} in {@code queue_snapshot} differenced between passes, minus the rows
 * actually written. A positive remainder is messages the ring dropped, the rate cap rejected,
 * or that arrived while the tap was not installed. There is deliberately no second gap metric.
 * The causes the drains recorded ({@link CaptureConsumer.Shortfall}) are named with it.
 *
 * <p>It is an estimate, and the product says so. Both terms come from sampled counters. What
 * it is reliable for is the thing that matters: a subscription steadily losing messages stops
 * being reported as healthy, and one that stops losing them is reported as healthy again.
 * Where it cannot be estimated — a divert filter captures part of the traffic by design — it is
 * stated as unavailable, never reported as zero (message-capture spec).
 */
@Component
@RequiredArgsConstructor
public class CaptureLoss {

    /** Below this, a difference is measurement noise rather than loss. */
    private static final long NOISE_FLOOR = 5;

    static final String FILTERED =
            "Loss cannot be estimated for this capture: its filter selects only part of what the address"
                    + " routes, so what was routed and what was captured are not expected to match.";

    private final MessageIndexSubscriptionRepository subscriptions;
    private final MessageCaptureNodeRepository captureNodes;
    private final QueueSnapshots snapshots;
    private final JdbcTemplate jdbc;
    private final CaptureConsumer consumers;
    private final CaptureAddresses addresses;

    /** What was routed and written at the previous pass, per (subscription, node), and when. */
    private final Map<String, Mark> marks = new ConcurrentHashMap<>();

    private record Mark(long routed, long captured, Instant at) {}

    /** Called at the end of each reconcile pass. Reads Postgres only — no broker call. */
    @Transactional
    public void measure(UUID clusterId) {
        for (MessageIndexSubscriptionEntity subscription : subscriptions.findByClusterId(clusterId)) {
            if (subscription.getMode() != CaptureMode.CAPTURE || !subscription.isEnabled()) {
                continue;
            }
            Set<String> covered = addresses.of(clusterId, subscription);
            List<MessageCaptureNodeEntity> nodes = captureNodes.findBySubscriptionId(subscription.getId());
            for (MessageCaptureNodeEntity node : nodes) {
                measureNode(clusterId, subscription, covered, node);
            }
            enforceStoredSizeBound(subscription, nodes);
        }
    }

    private void measureNode(
            UUID clusterId,
            MessageIndexSubscriptionEntity subscription,
            Set<String> covered,
            MessageCaptureNodeEntity node) {
        CaptureConsumer.Shortfall shortfall = consumers.takeShortfall(node.getNodeId(), subscription.getId());
        node.setHeldBytes(heldBytes(clusterId, subscription, covered, node.getNodeId()));
        node.setUpdatedAt(Instant.now());
        captureNodes.save(node);

        if (subscription.getFilterString() != null) {
            // Only a store failure can be stated; a count difference means nothing here.
            if (shortfall.storeFailure() != null) {
                degrade(node, lossDetail(0, null, shortfall));
            } else if (node.getCaptureState() == CaptureState.ACTIVE) {
                node.setCaptureDetail(FILTERED);
                captureNodes.save(node);
            }
            return;
        }

        long routed = routedToCovered(clusterId, covered, node.getNodeId());
        long captured = capturedRows(clusterId, subscription, covered, node.getNodeId());
        String key = subscription.getId() + "|" + node.getNodeId();
        Instant now = Instant.now();
        Mark previous = marks.put(key, new Mark(routed, captured, now));
        if (previous == null) {
            // The first pass has no interval to difference; it establishes the marks.
            if (shortfall.storeFailure() != null) {
                degrade(node, lossDetail(0, null, shortfall));
            }
            return;
        }
        long lost = Math.max(0, routed - previous.routed()) - Math.max(0, captured - previous.captured());
        if (lost > NOISE_FLOOR || shortfall.storeFailure() != null) {
            if (lost > NOISE_FLOOR) {
                node.setDroppedEstimate(node.getDroppedEstimate() + lost);
            }
            degrade(node, lossDetail(lost > NOISE_FLOOR ? lost : 0, previous.at(), shortfall));
            return;
        }
        if (node.getCaptureState() == CaptureState.DEGRADED) {
            // A full interval recorded everything routed: healthy again. Earlier loss stays in
            // droppedEstimate, and the detail of when it happened went with the last degraded pass.
            node.setCaptureState(CaptureState.ACTIVE);
            node.setCaptureDetail(null);
            node.setUpdatedAt(now);
            captureNodes.save(node);
        }
    }

    private void degrade(MessageCaptureNodeEntity node, String detail) {
        // Losing messages is not a failure of the tap — it is installed and draining —
        // so the state is DEGRADED rather than FAILED, and it says how much and why.
        if (node.getCaptureState() == CaptureState.ACTIVE) {
            node.setCaptureState(CaptureState.DEGRADED);
        }
        node.setCaptureDetail(detail);
        node.setUpdatedAt(Instant.now());
        captureNodes.save(node);
    }

    /** The loss in words, with the window it happened in and the causes the drains actually recorded. */
    static String lossDetail(long lost, Instant since, CaptureConsumer.Shortfall shortfall) {
        StringBuilder detail = new StringBuilder();
        if (lost > 0) {
            detail.append("About ")
                    .append(lost)
                    .append(" message")
                    .append(lost == 1 ? "" : "s")
                    .append(" routed to this node")
                    .append(since == null ? "" : " between " + since + " and " + Instant.now())
                    .append(" were not captured.");
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

    /**
     * What was routed to the covered addresses on this node. An address with several bound
     * queues routes each message to every one of them, so the counters are taken once per
     * address — the highest of its queues — rather than summed, which counted every message
     * once per queue and reported loss that never happened.
     */
    private long routedToCovered(UUID clusterId, Set<String> covered, UUID nodeId) {
        return snapshots.forCluster(clusterId).stream()
                .filter(row -> nodeId.equals(row.nodeId()))
                .filter(row -> covered.contains(row.address()))
                .collect(Collectors.toMap(QueueSnapshot::address, QueueSnapshot::messagesAdded, Math::max))
                .values()
                .stream()
                .mapToLong(Long::longValue)
                .sum();
    }

    /** Payload bytes this node's capture holds, which is the half of the bound an operator decides about. */
    private long heldBytes(
            UUID clusterId, MessageIndexSubscriptionEntity subscription, Set<String> covered, UUID nodeId) {
        Long bytes = capturedQuery(
                "SELECT coalesce(sum(coalesce(octet_length(body), 0) + pg_column_size(props)), 0)",
                clusterId,
                subscription,
                covered,
                nodeId);
        return bytes == null ? 0 : bytes;
    }

    private long capturedRows(
            UUID clusterId, MessageIndexSubscriptionEntity subscription, Set<String> covered, UUID nodeId) {
        Long count = capturedQuery("SELECT count(*)", clusterId, subscription, covered, nodeId);
        return count == null ? 0 : count;
    }

    /**
     * Captured rows are stored under their address. The window is the subscription's own
     * retention, so a query prunes to the partitions that can hold its rows instead of
     * scanning every partition on every pass.
     */
    private Long capturedQuery(
            String select,
            UUID clusterId,
            MessageIndexSubscriptionEntity subscription,
            Set<String> covered,
            UUID nodeId) {
        if (covered.isEmpty()) {
            return 0L;
        }
        String placeholders = String.join(", ", java.util.Collections.nCopies(covered.size(), "?"));
        List<Object> binds = new ArrayList<>();
        binds.add(clusterId);
        binds.add(nodeId);
        binds.add(Timestamp.from(Instant.now().minus(Duration.ofDays(subscription.getRetentionDays() + 1L))));
        binds.addAll(covered);
        return jdbc.queryForObject(
                select + " FROM message_index WHERE cluster_id = ? AND node_id = ? AND origin = 'CAPTURED'"
                        + " AND observed_at >= ? AND queue_name IN (" + placeholders + ')',
                Long.class,
                binds.toArray());
    }
}
