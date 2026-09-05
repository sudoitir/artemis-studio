package io.github.sudoitir.artemisstudio.web.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.github.sudoitir.artemisstudio.domain.topology.ClusterHealth;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The read side of the cluster API. Every type here is a projection for the
 * browser — <strong>no credential material appears in any of them</strong>.
 *
 * <p>Response fields carry {@code @Schema} so the generated OpenAPI document
 * declares requiredness and nullability honestly (ADR-0019): a field is
 * {@code requiredMode = REQUIRED} unless it is marked {@code nullable = true}.
 * The frontend's {@code schema.d.ts} is generated from this.
 */
public final class ClusterViews {

    private ClusterViews() {}

    /** One row in the cluster rail: rolled-up health, no detail. */
    public record ClusterSummary(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(nullable = true) String description,
            @Schema(requiredMode = REQUIRED) ClusterHealth.Level health,
            @Schema(requiredMode = REQUIRED) int nodeCount,
            @Schema(requiredMode = REQUIRED) Instant updatedAt,
            @Schema(nullable = true) UUID environmentId) {}

    /** The full cluster screen payload. */
    public record ClusterDetail(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(nullable = true) String description,
            @Schema(requiredMode = REQUIRED) TopologyView topology,
            @Schema(requiredMode = REQUIRED) CapabilitiesView capabilities,
            @Schema(requiredMode = REQUIRED) HealthView health,
            @Schema(nullable = true) UUID environmentId) {}

    /** One broker endpoint — a {@code broker_node} row, minus anything secret. */
    public record NodeEndpointView(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(nullable = true) String artemisNodeId,
            @Schema(nullable = true) String jolokiaUrl,
            @Schema(nullable = true) String coreUrl,
            @Schema(requiredMode = REQUIRED) String haRole,
            @Schema(requiredMode = REQUIRED) String state,
            @Schema(requiredMode = REQUIRED) boolean active,
            @Schema(nullable = true) Boolean replicaSync,
            @Schema(nullable = true) String version,
            @Schema(nullable = true) String lastError,
            @Schema(nullable = true) Instant lastSeenAt,
            @Schema(requiredMode = REQUIRED) boolean discovered,
            @Schema(requiredMode = REQUIRED) boolean manualOverride,
            @Schema(requiredMode = REQUIRED) boolean manageable) {}

    /** An HA pair (or standalone) keyed by NodeID. */
    public record LogicalNodeView(
            @Schema(nullable = true) String artemisNodeId,
            @Schema(requiredMode = REQUIRED) String splitBrain,
            @Schema(requiredMode = REQUIRED) boolean replicationBehind,
            @Schema(requiredMode = REQUIRED) List<NodeEndpointView> endpoints) {}

    /** {@code GET /clusters/{id}/topology}. */
    public record TopologyView(
            @Schema(requiredMode = REQUIRED) UUID clusterId,
            @Schema(requiredMode = REQUIRED) List<LogicalNodeView> nodes) {}

    /** One capability's Phase 1 assessment. */
    public record CapabilityView(
            @Schema(requiredMode = REQUIRED) String status,
            @Schema(requiredMode = REQUIRED) String reason,
            @Schema(nullable = true) String brokerXmlSnippet) {}

    /** {@code GET /clusters/{id}/capabilities}. */
    public record CapabilitiesView(
            @Schema(requiredMode = REQUIRED) CapabilityView managementRead,
            @Schema(requiredMode = REQUIRED) CapabilityView managementWrite,
            @Schema(requiredMode = REQUIRED) CapabilityView notifications,
            @Schema(requiredMode = REQUIRED) CapabilityView messageIo,
            @Schema(requiredMode = REQUIRED) CapabilityView slowConsumerDetection) {}

    /** {@code GET /clusters/{id}/health}. */
    public record HealthView(
            @Schema(requiredMode = REQUIRED) UUID clusterId,
            @Schema(requiredMode = REQUIRED) String level,
            @Schema(requiredMode = REQUIRED) List<String> liveEndpointNames,
            @Schema(requiredMode = REQUIRED) String splitBrain,
            @Schema(requiredMode = REQUIRED) boolean replicationBehind,
            @Schema(requiredMode = REQUIRED) List<String> notes) {}

    /** {@code POST /clusters?dryRun=true} — what a connection check found, nothing saved. */
    public record RegisterPreview(
            @Schema(requiredMode = REQUIRED) CapabilitiesView capabilities,
            @Schema(requiredMode = REQUIRED) int reachableSeeds,
            @Schema(requiredMode = REQUIRED) int discoveredNodes,
            @Schema(requiredMode = REQUIRED) TopologyView topology) {}
}
