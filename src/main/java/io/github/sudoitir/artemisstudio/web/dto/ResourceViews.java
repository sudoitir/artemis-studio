package io.github.sudoitir.artemisstudio.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The cross-node resource API (ADR-0017). Every row is tagged with the logical
 * node it came from; queues additionally roll their per-node cells up into
 * cluster totals. Nothing secret appears here.
 */
public final class ResourceViews {

    private ResourceViews() {}

    /** A page of any resource list. {@code count} is the total across all nodes, not the page size. */
    public record PagedView<T>(List<T> data, long count, int page, int pageSize) {}

    // ---- queues (aggregated from queue_snapshot) --------------------------

    public record QueueView(
            String address,
            String queueName,
            String routingType,
            boolean durable,
            long totalMessageCount,
            long totalConsumerCount,
            long totalDeliveringCount,
            long totalScheduledCount,
            int nodesPresent,
            int nodesTotal,
            List<QueueNodeCell> perNode) {}

    /**
     * One node's contribution to a queue row.
     *
     * @param stale the node's last sweep is older than the freshness window — the
     *     numbers are the last seen, not dropped
     */
    public record QueueNodeCell(
            UUID nodeId,
            String nodeName,
            boolean stale,
            Instant lastSeenAt,
            long messageCount,
            long consumerCount,
            long deliveringCount,
            long scheduledCount) {}

    // ---- live-through resources (one POST per serving node, merged) -------

    public record AddressView(
            UUID nodeId, String nodeName, String name, String routingTypes, long queueCount, long messageCount) {}

    public record ConsumerView(
            UUID nodeId,
            String nodeName,
            String consumerId,
            String sessionId,
            String queueName,
            String address,
            String protocol,
            long messagesDelivered,
            long messagesAcknowledged,
            String status) {}

    public record SessionView(
            UUID nodeId,
            String nodeName,
            String sessionId,
            String user,
            String connectionId,
            long consumerCount,
            long producerCount,
            String creationTime) {}

    public record ConnectionView(
            UUID nodeId,
            String nodeName,
            String connectionId,
            String remoteAddress,
            String protocol,
            String clientId,
            long sessionCount,
            String creationTime) {}

    public record ProducerView(
            UUID nodeId,
            String nodeName,
            String producerId,
            String name,
            String sessionId,
            String address,
            String protocol,
            long messagesSent) {}
}
