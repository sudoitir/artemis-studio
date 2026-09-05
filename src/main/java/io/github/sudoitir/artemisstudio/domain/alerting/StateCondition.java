package io.github.sudoitir.artemisstudio.domain.alerting;

import io.github.sudoitir.artemisstudio.domain.topology.ClusterHealth;
import io.github.sudoitir.artemisstudio.domain.topology.HaStateEvaluator;
import io.github.sudoitir.artemisstudio.domain.topology.LogicalNode;
import io.github.sudoitir.artemisstudio.domain.topology.NodeEndpoint;
import io.github.sudoitir.artemisstudio.domain.topology.SplitBrainRegistry;
import io.github.sudoitir.artemisstudio.domain.topology.SplitBrainStatus;
import io.github.sudoitir.artemisstudio.mapper.BrokerNodeMapper;
import io.github.sudoitir.artemisstudio.persist.AlertRuleEntity;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeRepository;
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

    private final BrokerNodeRepository nodes;
    private final BrokerNodeMapper nodeMapper;
    private final HaStateEvaluator evaluator;
    private final SplitBrainRegistry splitBrainRegistry;

    @Override
    public Evaluation evaluate(UUID clusterId, AlertRuleEntity rule) {
        List<BrokerNodeEntity> rows = nodes.findByClusterIdOrderByNameAsc(clusterId);
        return switch (rule.getStateCondition()) {
            case "SPLIT_BRAIN" -> splitBrain(clusterId, rows);
            case "NODE_DOWN" -> nodeDown(rows);
            case "REPLICATION_BEHIND" -> replicationBehind(rows);
            case "CLUSTER_DEGRADED" -> clusterDegraded(clusterId, rows);
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

    private Evaluation clusterDegraded(UUID clusterId, List<BrokerNodeEntity> rows) {
        List<NodeEndpoint> endpoints = nodeMapper.toEndpoints(rows);
        List<LogicalNode> logical = evaluator.toLogicalNodes(endpoints, splitBrainRegistry.statusesFor(clusterId));
        ClusterHealth health = evaluator.toHealth(clusterId, logical);
        Set<String> universe = Set.of(CLUSTER_SUBJECT);
        boolean degraded =
                health.level() == ClusterHealth.Level.DEGRADED || health.level() == ClusterHealth.Level.CRITICAL;
        return new Evaluation(universe, degraded ? Map.of(CLUSTER_SUBJECT, 1.0) : Map.of());
    }
}
