package io.github.sudoitir.artemisstudio.platform.clusters;

import io.github.sudoitir.artemisstudio.kernel.audit.AuditEvent;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditService;
import io.github.sudoitir.artemisstudio.kernel.security.ActorResolver;
import io.github.sudoitir.artemisstudio.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsService;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerConnectionException;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerSettings;
import io.github.sudoitir.artemisstudio.platform.broker.BulkCapExceededException;
import io.github.sudoitir.artemisstudio.platform.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.platform.broker.ManagementRefusal;
import io.github.sudoitir.artemisstudio.platform.clusters.LifecycleOutcome.NodeOutcome;
import io.github.sudoitir.artemisstudio.platform.clusters.LifecycleOutcome.NodeStatus;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.BrokerNodeRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs one cluster-wide broker write the same way every time (ADR-0071, ADR-0049):
 *
 * <ol>
 *   <li>the caller's permission on the cluster;
 *   <li>one target per logical node, liveness from the polled {@code Active} attribute
 *       (non-negotiable #4);
 *   <li>the audit row, written before any broker call (non-negotiable #3);
 *   <li>a per-node estimate, so the bulk cap sees the whole blast radius (ADR-0022);
 *   <li>a dry run returns {@code WOULD_APPLY} per node and writes nothing;
 *   <li>over the cap without an override, the refusal is audited and thrown;
 *   <li>the fan-out, one rate-limited call per live node, where a node's failure never
 *       aborts the others and nothing is rolled back (D3);
 *   <li>the audit row finished with the per-node detail (D4);
 *   <li>the caller's topic signal, after commit.
 * </ol>
 *
 * <p>No database transaction is held across the fan-out: the audit row and its outcome
 * each commit on their own (ADR-0078), and a broker call must never pin a pooled
 * connection for N nodes × the read timeout.
 */
@Component
@RequiredArgsConstructor
public class BrokerCommands {

    private final BrokerNodeRepository brokerNodes;
    private final BrokerConnections connections;
    private final AuditService audit;
    private final ActorResolver actorResolver;
    private final SettingsService settings;
    private final ClusterAccessGuard clusterAccess;
    private final CapabilityLedger capabilities;

    /** What one node's attempt does; returns the state the node reached. */
    @FunctionalInterface
    public interface NodeAction {
        NodeStatus apply(JolokiaBrokerClient client, String brokerMbean);
    }

    /** How much one node's attempt would destroy, for the cap check (ADR-0022). */
    @FunctionalInterface
    public interface NodeEstimate {
        long apply(JolokiaBrokerClient client);
    }

    /**
     * A destructive command's estimate.
     *
     * @param label what is counted, as an operator reads it: "message count"
     * @param unknownNeedsOverride whether a node that could not be counted makes the total a
     *     floor that needs the same explicit override an over-cap total does
     */
    public record Estimate(String label, boolean unknownNeedsOverride, NodeEstimate count) {}

    /**
     * One command.
     *
     * @param estimate {@code null} for a command that destroys nothing
     * @param auditDetail shapes the per-node outcomes into the audit row's detail
     * @param signal the topic signal published after commit
     */
    @Builder
    public record Command(
            UUID clusterId,
            String permission,
            String auditAction,
            String targetType,
            String targetName,
            Map<String, ?> params,
            boolean dryRun,
            boolean override,
            NodeAction action,
            Estimate estimate,
            Function<List<NodeOutcome>, Object> auditDetail,
            Runnable signal) {

        public Command {
            params = params == null ? Map.of() : params;
            auditDetail = auditDetail == null ? nodes -> nodes : auditDetail;
            signal = signal == null ? () -> {} : signal;
        }
    }

    private record Target(BrokerNodeEntity node, boolean live) {}

    public LifecycleOutcome run(Command c) {
        clusterAccess.requireCluster(c.clusterId(), c.permission());
        List<Target> targets = targets(c.clusterId());
        long cap = settings.intValue(BrokerSettings.BULK_CAP);

        AuditEvent event = audit.begin(
                actorResolver.resolve(),
                c.auditAction(),
                c.targetType(),
                c.targetName(),
                c.clusterId(),
                null,
                c.params(),
                c.dryRun());

        Map<UUID, Long> estimates = new LinkedHashMap<>();
        long total = 0;
        if (c.estimate() != null) {
            for (Target t : targets) {
                if (!t.live()) {
                    continue;
                }
                try {
                    estimates.put(t.node().getId(), c.estimate().count().apply(clientFor(c.clusterId(), t.node())));
                } catch (ManagementRefusal e) {
                    // Nothing to destroy here — an absent resource counts as zero, and
                    // the action reports the node as ALREADY.
                    estimates.put(t.node().getId(), 0L);
                } catch (BrokerConnectionException e) {
                    estimates.put(t.node().getId(), null);
                }
            }
            total = estimates.values().stream()
                    .filter(Objects::nonNull)
                    .mapToLong(Long::longValue)
                    .sum();
        }
        // A node that could not be counted makes the total a floor, not a figure.
        boolean incomplete = estimates.containsValue(null);
        boolean overCap = c.estimate() != null
                && (total > cap || (incomplete && c.estimate().unknownNeedsOverride()));

        List<NodeOutcome> outcomes = new ArrayList<>();
        if (c.dryRun()) {
            for (Target t : targets) {
                UUID id = t.node().getId();
                if (!t.live()) {
                    outcomes.add(NodeOutcome.skipped(id, t.node().getName()));
                    continue;
                }
                boolean unknown = c.estimate() != null && estimates.get(id) == null;
                // Stated, never omitted: an absent count reads as zero.
                String note = unknown
                        ? "This node did not answer, so its " + c.estimate().label() + " is unknown."
                        : null;
                outcomes.add(new NodeOutcome(id, t.node().getName(), NodeStatus.WOULD_APPLY, estimates.get(id), note));
            }
            audit.finish(event, false, total, null, c.auditDetail().apply(outcomes));
            return new LifecycleOutcome(true, cap, overCap, outcomes);
        }

        if (overCap && !c.override()) {
            String reason = total <= cap && incomplete
                    ? "At least one node did not report its " + c.estimate().label()
                            + ", so the blast radius is not known (" + total + " counted, cap " + cap + ")."
                    : "Over the safety cap (" + total + " > " + cap + ").";
            audit.finish(event, true, total, reason, c.auditDetail().apply(outcomes));
            throw new BulkCapExceededException(total, cap);
        }

        for (Target t : targets) {
            if (!t.live()) {
                outcomes.add(NodeOutcome.skipped(t.node().getId(), t.node().getName()));
                continue;
            }
            outcomes.add(applyTo(c, t, estimates.get(t.node().getId())));
        }

        LifecycleOutcome outcome = new LifecycleOutcome(false, cap, overCap, outcomes);
        audit.finish(
                event,
                outcome.anyFailed(),
                outcome.totalAffected(),
                failureSummary(outcomes),
                c.auditDetail().apply(outcomes));
        signalAfterCommit(c.signal());
        return outcome;
    }

    /**
     * One node's attempt. Every failure mode becomes a node outcome rather than aborting
     * the fan-out: the nodes that succeeded are not reverted, and the divergence is what
     * gets reported (D3).
     */
    private NodeOutcome applyTo(Command c, Target t, Long estimated) {
        UUID clusterId = c.clusterId();
        UUID nodeId = t.node().getId();
        String nodeName = t.node().getName();
        try {
            JolokiaBrokerClient client = clientFor(clusterId, t.node());
            NodeStatus status = c.action().apply(client, client.resolveBrokerObjectName());
            capabilities.recordWriteSucceeded(clusterId);
            return new NodeOutcome(nodeId, nodeName, status, status == NodeStatus.APPLIED ? estimated : null, null);
        } catch (ManagementRefusal e) {
            if (e.kind() == ManagementRefusal.Kind.ALREADY) {
                // Already in the requested state. The broker answered, so this is still
                // evidence the connection can write.
                capabilities.recordWriteSucceeded(clusterId);
                return new NodeOutcome(nodeId, nodeName, NodeStatus.ALREADY, null, null);
            }
            // An argument refusal says the request is wrong, not that the connection
            // cannot write — it must never disable the capability (D5).
            return NodeOutcome.failed(nodeId, nodeName, e.getMessage());
        } catch (BrokerConnectionException e) {
            if (e.kind() == BrokerConnectionException.Kind.UNAUTHORIZED) {
                capabilities.recordWriteRefused(clusterId, e.getMessage());
            }
            return NodeOutcome.failed(nodeId, nodeName, e.getMessage());
        }
    }

    /**
     * One target per <em>logical</em> node. A primary and its synced backup share an
     * Artemis NodeID; only one of them is live, and the backup refuses management writes,
     * so targeting the pair twice would report a healthy backup as skipped on every command.
     */
    private List<Target> targets(UUID clusterId) {
        Map<String, List<BrokerNodeEntity>> logical = new LinkedHashMap<>();
        for (BrokerNodeEntity node : brokerNodes.findByClusterIdOrderByNameAsc(clusterId)) {
            if (node.getJolokiaUrl() == null) {
                continue;
            }
            String key = node.getArtemisNodeId() != null ? node.getArtemisNodeId() : "id:" + node.getId();
            logical.computeIfAbsent(key, k -> new ArrayList<>()).add(node);
        }
        List<Target> targets = new ArrayList<>();
        for (List<BrokerNodeEntity> group : logical.values()) {
            group.stream()
                    .filter(n -> Boolean.TRUE.equals(n.getActive()))
                    .findFirst()
                    .ifPresentOrElse(
                            live -> targets.add(new Target(live, true)),
                            () -> targets.add(new Target(group.get(0), false)));
        }
        if (targets.isEmpty()) {
            throw new BrokerConnectionException(
                    BrokerConnectionException.Kind.UNREACHABLE,
                    "This cluster has no node with a management URL, so nothing can be changed on it.");
        }
        return targets;
    }

    private static String failureSummary(List<NodeOutcome> outcomes) {
        List<String> failed = outcomes.stream()
                .filter(n -> n.status() == NodeStatus.FAILED)
                .map(n -> n.nodeName() + ": " + n.error())
                .sorted(Comparator.naturalOrder())
                .toList();
        return failed.isEmpty() ? null : String.join(" | ", failed);
    }

    /** Every request the client sends waits for the node's ceiling itself (ADR-0076). */
    private JolokiaBrokerClient clientFor(UUID clusterId, BrokerNodeEntity node) {
        return connections.forCluster(clusterId, node.getJolokiaUrl());
    }

    private static void signalAfterCommit(Runnable signal) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    signal.run();
                }
            });
        } else {
            signal.run();
        }
    }
}
