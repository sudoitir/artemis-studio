package io.github.sudoitir.artemisstudio.web.dto;

import io.github.sudoitir.artemisstudio.domain.topology.ClusterHealth;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The read side of the cluster API. Every type here is a projection for the
 * browser — <strong>no credential material appears in any of them</strong>.
 */
public final class ClusterViews {

    private ClusterViews() {}

    /** One row in the cluster rail: rolled-up health, no detail. */
    public record ClusterSummary(
            UUID id, String name, String description, ClusterHealth.Level health, int nodeCount, Instant updatedAt) {}

    /** The full cluster screen payload. */
    public record ClusterDetail(
            UUID id,
            String name,
            String description,
            TopologyView topology,
            CapabilitiesView capabilities,
            HealthView health) {}

    /** One broker endpoint — a {@code broker_node} row, minus anything secret. */
    public record NodeEndpointView(
            UUID id,
            String name,
            String artemisNodeId,
            String jolokiaUrl,
            String coreUrl,
            String haRole,
            String state,
            boolean active,
            Boolean replicaSync,
            String version,
            String lastError,
            Instant lastSeenAt,
            boolean discovered,
            boolean manualOverride,
            boolean manageable) {}

    /** An HA pair (or standalone) keyed by NodeID. */
    public record LogicalNodeView(
            String artemisNodeId, String splitBrain, boolean replicationBehind, List<NodeEndpointView> endpoints) {}

    /** {@code GET /clusters/{id}/topology}. */
    public record TopologyView(UUID clusterId, List<LogicalNodeView> nodes) {}

    /** One capability's Phase 1 assessment. */
    public record CapabilityView(String status, String reason, String brokerXmlSnippet) {}

    /** {@code GET /clusters/{id}/capabilities}. */
    public record CapabilitiesView(
            CapabilityView managementRead,
            CapabilityView managementWrite,
            CapabilityView notifications,
            CapabilityView messageIo) {}

    /** {@code GET /clusters/{id}/health}. */
    public record HealthView(
            UUID clusterId,
            String level,
            List<String> liveEndpointNames,
            String splitBrain,
            boolean replicationBehind,
            List<String> notes) {}

    /** {@code POST /clusters?dryRun=true} — what a connection check found, nothing saved. */
    public record RegisterPreview(
            CapabilitiesView capabilities, int reachableSeeds, int discoveredNodes, List<String> nodeNames) {}
}
