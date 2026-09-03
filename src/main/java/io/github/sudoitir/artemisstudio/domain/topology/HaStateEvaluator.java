package io.github.sudoitir.artemisstudio.domain.topology;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Derives HA role, node state, and corroborated split-brain from polled broker
 * attributes. Never reads broker <em>config</em> for HA facts (non-negotiable #4).
 *
 * <p>Split-brain corroboration (ADR-0012) keeps one small piece of state: per
 * NodeID, the refresh cycle at which a same-cycle dual-active was first seen. It
 * lives in memory only.
 */
// ponytail: in-memory corroboration ratchet; a restart resets the window, costing
// at most one extra ~5s cycle before a real split-brain re-escalates to CRITICAL.
@Component
public class HaStateEvaluator {

    private final Map<String, Long> firstSuspectedCycle = new ConcurrentHashMap<>();

    /** {@code Started} → node state. Health is gated on this, not on {@code Active}. */
    public String deriveState(Boolean started) {
        if (started == null) {
            return "UNKNOWN";
        }
        return started ? "STARTED" : "STOPPED";
    }

    /** {@code Backup} / {@code Clustered} → HA role. */
    public String deriveHaRole(Boolean backup, Boolean clustered) {
        if (Boolean.TRUE.equals(backup)) {
            return "BACKUP";
        }
        if (Boolean.TRUE.equals(clustered)) {
            return "PRIMARY";
        }
        return "STANDALONE";
    }

    /**
     * Corroborated split-brain for one logical node. Advances the ratchet and is
     * safe to call between cycles — unchanged input never escalates.
     */
    public SplitBrainStatus evaluateSplitBrain(String nodeId, List<NodeEndpoint> endpoints) {
        List<NodeEndpoint> active =
                endpoints.stream().filter(NodeEndpoint::live).toList();

        if (nodeId == null || active.size() < 2) {
            firstSuspectedCycle.remove(nodeId);
            return SplitBrainStatus.NONE;
        }

        Set<Long> cycles = active.stream().map(NodeEndpoint::observedCycle).collect(Collectors.toSet());
        if (cycles.contains(null) || cycles.size() != 1) {
            // Readings from different cycles are sampling skew, not evidence — this
            // is the guard that stops a planned failover raising a false CRITICAL.
            firstSuspectedCycle.remove(nodeId);
            return SplitBrainStatus.NONE;
        }

        long cycle = cycles.iterator().next();
        Long firstSeen = firstSuspectedCycle.putIfAbsent(nodeId, cycle);
        if (firstSeen != null && cycle > firstSeen) {
            return SplitBrainStatus.CRITICAL;
        }
        return SplitBrainStatus.SUSPECTED;
    }

    /** Group endpoints into logical nodes by NodeID and attach the derived HA signals. */
    public List<LogicalNode> toLogicalNodes(List<NodeEndpoint> endpoints) {
        Map<String, List<NodeEndpoint>> byNode = new LinkedHashMap<>();
        for (NodeEndpoint e : endpoints) {
            String key = e.artemisNodeId() != null ? e.artemisNodeId() : "endpoint:" + e.id();
            byNode.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
        }

        List<LogicalNode> nodes = new ArrayList<>();
        byNode.forEach((key, eps) -> {
            String nodeId = eps.get(0).artemisNodeId();
            SplitBrainStatus splitBrain = evaluateSplitBrain(nodeId, eps);
            boolean behind = eps.stream().anyMatch(e -> e.isBackup() && Boolean.FALSE.equals(e.replicaSync()));
            nodes.add(new LogicalNode(nodeId, List.copyOf(eps), splitBrain, behind));
        });
        return nodes;
    }

    /** Roll a cluster's logical nodes up into one health verdict. */
    public ClusterHealth toHealth(UUID clusterId, List<LogicalNode> nodes) {
        boolean neverContacted =
                nodes.stream().flatMap(n -> n.endpoints().stream()).allMatch(e -> e.lastSeenAt() == null);
        if (nodes.isEmpty() || neverContacted) {
            return new ClusterHealth(
                    clusterId,
                    ClusterHealth.Level.UNKNOWN,
                    List.of(),
                    SplitBrainStatus.NONE,
                    false,
                    List.of("No endpoint has been contacted yet."));
        }

        List<String> live = nodes.stream()
                .flatMap(n -> n.serving().stream())
                .map(NodeEndpoint::name)
                .toList();
        SplitBrainStatus worst = nodes.stream()
                .map(LogicalNode::splitBrain)
                .max(Comparator.naturalOrder())
                .orElse(SplitBrainStatus.NONE);
        boolean behind = nodes.stream().anyMatch(LogicalNode::replicationBehind);
        boolean stoppedManageable = nodes.stream()
                .flatMap(n -> n.endpoints().stream())
                .anyMatch(e -> e.manageable() && ("STOPPED".equals(e.state()) || e.unreachable()));

        List<String> notes = new ArrayList<>();
        if (worst == SplitBrainStatus.CRITICAL) {
            notes.add("Two nodes are live in one pair. Producers may be splitting across them and the"
                    + " journals are diverging — check the cluster's quorum configuration.");
        } else if (worst == SplitBrainStatus.SUSPECTED) {
            notes.add("Checking — two nodes are reporting active. This is normal for a few seconds"
                    + " during a failover.");
        }
        if (behind) {
            notes.add("The backup is not caught up. A failover right now would lose whatever has not"
                    + " replicated yet.");
        }
        if (stoppedManageable) {
            notes.add("A managed endpoint is stopped or unreachable.");
        }

        ClusterHealth.Level level;
        if (worst == SplitBrainStatus.CRITICAL) {
            level = ClusterHealth.Level.CRITICAL;
        } else if (worst == SplitBrainStatus.SUSPECTED || behind || stoppedManageable) {
            level = ClusterHealth.Level.DEGRADED;
        } else {
            level = ClusterHealth.Level.OK;
        }

        return new ClusterHealth(clusterId, level, live, worst, behind, List.copyOf(notes));
    }
}
