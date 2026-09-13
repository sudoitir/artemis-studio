package io.github.sudoitir.artemisstudio.domain.alerting;

import io.github.sudoitir.artemisstudio.domain.topology.ClusterHealth;
import io.github.sudoitir.artemisstudio.domain.topology.HaStateEvaluator;
import io.github.sudoitir.artemisstudio.domain.topology.LogicalNode;
import io.github.sudoitir.artemisstudio.domain.topology.NodeEndpoint;
import io.github.sudoitir.artemisstudio.domain.topology.SplitBrainRegistry;
import io.github.sudoitir.artemisstudio.domain.topology.SplitBrainStatus;
import io.github.sudoitir.artemisstudio.mapper.BrokerNodeMapper;
import io.github.sudoitir.artemisstudio.persist.AlertRuleEntity;
import io.github.sudoitir.artemisstudio.persist.BrokerConfigNodeStateEntity;
import io.github.sudoitir.artemisstudio.persist.BrokerConfigNodeStateRepository;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeRepository;
import io.github.sudoitir.artemisstudio.service.ClockOffsetService;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Reads the already-computed HA read models — never a metric row (design.md
 * decision 1). {@code SPLIT_BRAIN} fires only on a corroborated
 * {@link SplitBrainStatus#CRITICAL}, never on the first-sighting
 * {@code SUSPECTED} verdict (ADR-0012).
 */
@Component
@RequiredArgsConstructor
public class StateCondition implements AlertCondition {

    private static final String CLUSTER_SUBJECT = "cluster";

    /** The one subject that is not a broker: Studio's own host, when it is the suspect. */
    private static final String STUDIO_SUBJECT = "studio";

    private final BrokerNodeRepository nodes;
    private final io.github.sudoitir.artemisstudio.service.ClockOffsetService clocks;
    private final BrokerNodeMapper nodeMapper;
    private final HaStateEvaluator evaluator;
    private final SplitBrainRegistry splitBrainRegistry;
    private final BrokerConfigNodeStateRepository configStates;

    @Override
    public Evaluation evaluate(UUID clusterId, AlertRuleEntity rule) {
        List<BrokerNodeEntity> rows = nodes.findByClusterIdOrderByNameAsc(clusterId);
        return switch (rule.getStateCondition()) {
            case "SPLIT_BRAIN" -> splitBrain(clusterId, rows);
            case "NODE_DOWN" -> nodeDown(rows);
            case "REPLICATION_BEHIND" -> replicationBehind(rows);
            case "CLUSTER_DEGRADED" -> clusterDegraded(clusterId, rows);
            case "CLOCK_SKEW" -> clockSkew(clusterId, rows);
            case "CONFIG_DRIFT" -> configDrift(clusterId);
            default -> Evaluation.EMPTY;
        };
    }

    private Evaluation splitBrain(UUID clusterId, List<BrokerNodeEntity> rows) {
        List<LogicalNode> logical =
                evaluator.toLogicalNodes(nodeMapper.toEndpoints(rows), splitBrainRegistry.statusesFor(clusterId));
        boolean critical = logical.stream().anyMatch(n -> n.splitBrain() == SplitBrainStatus.CRITICAL);
        Set<String> universe = Set.of(CLUSTER_SUBJECT);
        return new Evaluation(universe, critical ? Map.of(CLUSTER_SUBJECT, 1.0) : Map.of());
    }

    private Evaluation nodeDown(List<BrokerNodeEntity> rows) {
        Set<String> universe = new HashSet<>();
        Map<String, Double> active = new HashMap<>();
        for (BrokerNodeEntity node : rows) {
            if (node.getJolokiaUrl() == null) {
                continue; // not manageable — nothing to be "down" from Studio's view
            }
            String key = "node:" + node.getId();
            universe.add(key);
            boolean down = "STOPPED".equals(node.getState()) || node.getLastError() != null;
            if (down) {
                active.put(key, 1.0);
            }
        }
        return new Evaluation(Set.copyOf(universe), Map.copyOf(active));
    }

    private Evaluation replicationBehind(List<BrokerNodeEntity> rows) {
        Set<String> universe = new HashSet<>();
        Map<String, Double> active = new HashMap<>();
        for (BrokerNodeEntity node : rows) {
            if (!"BACKUP".equals(node.getHaRole())) {
                continue;
            }
            String key = "node:" + node.getId();
            universe.add(key);
            if (Boolean.FALSE.equals(node.getReplicaSync())) {
                active.put(key, 1.0);
            }
        }
        return new Evaluation(Set.copyOf(universe), Map.copyOf(active));
    }

    /**
     * A clock that disagrees with Studio's beyond tolerance (ADR-0053).
     *
     * <p>Subject-keyed by node so each tracks and silences independently, with one
     * extra subject for the case Studio cannot measure directly: when every node in
     * the estate disagrees the same way, the common factor is Studio's own host, and
     * the alert says so rather than blaming every broker at once.
     *
     * <p>The universe is only what has actually been measured. A node whose Jolokia
     * agent strips the response timestamp is unknown, not in agreement, and putting
     * it in the universe would resolve an alert on the strength of no evidence.
     */
    private Evaluation clockSkew(UUID clusterId, List<BrokerNodeEntity> rows) {
        ClockOffsetService.Assessment assessment = clocks.assessmentFor(clusterId);
        if (assessment.verdict() == ClockOffsetService.Verdict.UNKNOWN) {
            return Evaluation.EMPTY;
        }
        Set<String> universe = new HashSet<>();
        Map<String, Double> active = new HashMap<>();
        for (ClockOffsetService.NodeSkew measured : assessment.measured()) {
            universe.add("node:" + measured.nodeId());
        }
        if (assessment.verdict() == ClockOffsetService.Verdict.STUDIO_SUSPECT) {
            universe.add(STUDIO_SUBJECT);
            active.put(STUDIO_SUBJECT, 1.0);
        } else {
            for (ClockOffsetService.NodeSkew skewed : assessment.skewed()) {
                active.put("node:" + skewed.nodeId(), (double)
                        Math.abs(skewed.offset().offsetMs()));
            }
        }
        return new Evaluation(Set.copyOf(universe), Map.copyOf(active));
    }

    private Evaluation clusterDegraded(UUID clusterId, List<BrokerNodeEntity> rows) {
        List<NodeEndpoint> endpoints = nodeMapper.toEndpoints(rows);
        List<LogicalNode> logical = evaluator.toLogicalNodes(endpoints, splitBrainRegistry.statusesFor(clusterId));
        ClusterHealth health = evaluator.toHealth(clusterId, logical);
        Set<String> universe = Set.of(CLUSTER_SUBJECT);
        boolean degraded =
                health.level() == ClusterHealth.Level.DEGRADED || health.level() == ClusterHealth.Level.CRITICAL;
        return new Evaluation(universe, degraded ? Map.of(CLUSTER_SUBJECT, 1.0) : Map.of());
    }

    /**
     * A node whose last configuration evaluation found drift (ADR-0067 D8). Read
     * from the recorded per-node state, never from a broker: the alert is a view of
     * the last evaluation, at the interval the operator set. Only evaluated nodes are
     * in the universe — one that was unreachable or not live cannot resolve a firing
     * on the strength of no evidence.
     */
    private Evaluation configDrift(UUID clusterId) {
        Set<String> universe = new HashSet<>();
        Map<String, Double> active = new HashMap<>();
        for (BrokerConfigNodeStateEntity state : configStates.findByClusterId(clusterId)) {
            BrokerConfigNodeStateEntity.State s = state.state();
            if (s != BrokerConfigNodeStateEntity.State.IN_SYNC && s != BrokerConfigNodeStateEntity.State.DRIFTED) {
                continue;
            }
            String key = "node:" + state.getNodeId();
            universe.add(key);
            if (s == BrokerConfigNodeStateEntity.State.DRIFTED) {
                active.put(key, 1.0);
            }
        }
        return new Evaluation(Set.copyOf(universe), Map.copyOf(active));
    }
}
