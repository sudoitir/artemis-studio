package io.github.sudoitir.artemisstudio.web.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The cross-node resource API (ADR-0017). Every row is tagged with the logical
 * node it came from; queues additionally roll their per-node cells up into
 * cluster totals. Nothing secret appears here.
 *
 * <p>{@code @Schema} on every component so the generated OpenAPI document (and
 * the frontend's {@code schema.d.ts}) declares requiredness and nullability
 * honestly (ADR-0019): required unless marked {@code nullable = true}.
 */
public final class ResourceViews {

    private ResourceViews() {}

    /** A page of any resource list. {@code count} is the total across all nodes, not the page size. */
    public record PagedView<T>(
            @Schema(requiredMode = REQUIRED) List<T> data,
            @Schema(requiredMode = REQUIRED) long count,
            @Schema(requiredMode = REQUIRED) int page,
            @Schema(requiredMode = REQUIRED) int pageSize) {}

    // ---- queues (aggregated from queue_snapshot) --------------------------

    public record QueueView(
            @Schema(requiredMode = REQUIRED) String address,
            @Schema(requiredMode = REQUIRED) String queueName,
            @Schema(requiredMode = REQUIRED) String routingType,
            @Schema(requiredMode = REQUIRED) boolean durable,
            @Schema(requiredMode = REQUIRED) long totalMessageCount,
            @Schema(requiredMode = REQUIRED) long totalConsumerCount,
            @Schema(requiredMode = REQUIRED) long totalDeliveringCount,
            @Schema(requiredMode = REQUIRED) long totalScheduledCount,
            @Schema(requiredMode = REQUIRED) int nodesPresent,
            @Schema(requiredMode = REQUIRED) int nodesTotal,
            @Schema(requiredMode = REQUIRED) List<QueueNodeCell> perNode) {}

    /**
     * One node's contribution to a queue row.
     *
     * @param stale the node's last sweep is older than the freshness window — the
     *     numbers are the last seen, not dropped
     */
    public record QueueNodeCell(
            @Schema(requiredMode = REQUIRED) UUID nodeId,
            @Schema(requiredMode = REQUIRED) String nodeName,
            @Schema(requiredMode = REQUIRED) boolean stale,
            @Schema(nullable = true) Instant lastSeenAt,
            @Schema(requiredMode = REQUIRED) long messageCount,
            @Schema(requiredMode = REQUIRED) long consumerCount,
            @Schema(requiredMode = REQUIRED) long deliveringCount,
            @Schema(requiredMode = REQUIRED) long scheduledCount) {}

    // ---- live-through resources (one POST per serving node, merged) -------

    public record AddressView(
            @Schema(requiredMode = REQUIRED) UUID nodeId,
            @Schema(requiredMode = REQUIRED) String nodeName,
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(nullable = true) String routingTypes,
            @Schema(requiredMode = REQUIRED) long queueCount,
            @Schema(requiredMode = REQUIRED) long messageCount) {}

    public record ConsumerView(
            @Schema(requiredMode = REQUIRED) UUID nodeId,
            @Schema(requiredMode = REQUIRED) String nodeName,
            @Schema(nullable = true) String consumerId,
            @Schema(nullable = true) String sessionId,
            @Schema(nullable = true) String queueName,
            @Schema(nullable = true) String address,
            @Schema(nullable = true) String protocol,
            @Schema(requiredMode = REQUIRED) long messagesDelivered,
            @Schema(requiredMode = REQUIRED) long messagesAcknowledged,
            @Schema(nullable = true) String status) {}

    public record SessionView(
            @Schema(requiredMode = REQUIRED) UUID nodeId,
            @Schema(requiredMode = REQUIRED) String nodeName,
            @Schema(nullable = true) String sessionId,
            @Schema(nullable = true) String user,
            @Schema(nullable = true) String connectionId,
            @Schema(requiredMode = REQUIRED) long consumerCount,
            @Schema(requiredMode = REQUIRED) long producerCount,
            @Schema(nullable = true) String creationTime) {}

    public record ConnectionView(
            @Schema(requiredMode = REQUIRED) UUID nodeId,
            @Schema(requiredMode = REQUIRED) String nodeName,
            @Schema(nullable = true) String connectionId,
            @Schema(nullable = true) String remoteAddress,
            @Schema(nullable = true) String protocol,
            @Schema(nullable = true) String clientId,
            @Schema(requiredMode = REQUIRED) long sessionCount,
            @Schema(nullable = true) String creationTime) {}

    public record ProducerView(
            @Schema(requiredMode = REQUIRED) UUID nodeId,
            @Schema(requiredMode = REQUIRED) String nodeName,
            @Schema(nullable = true) String producerId,
            @Schema(nullable = true) String name,
            @Schema(nullable = true) String sessionId,
            @Schema(nullable = true) String address,
            @Schema(nullable = true) String protocol,
            @Schema(requiredMode = REQUIRED) long messagesSent) {}
}
