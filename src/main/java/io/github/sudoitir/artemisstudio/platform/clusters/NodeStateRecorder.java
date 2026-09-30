package io.github.sudoitir.artemisstudio.platform.clusters;

import static io.github.sudoitir.artemisstudio.platform.broker.JolokiaJson.bool;
import static io.github.sudoitir.artemisstudio.platform.broker.JolokiaJson.boxedBool;
import static io.github.sudoitir.artemisstudio.platform.broker.JolokiaJson.text;

import io.github.sudoitir.artemisstudio.platform.broker.NodeEndpoint;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.BrokerNodeEntity.HaObservation;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.BrokerNodeRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * The scheduler's persistence adapter: each method is a short transaction that
 * runs <em>after</em> the network I/O, never around it (ADR-0015 — "network I/O
 * never inside a DB transaction").
 */
@Component
@RequiredArgsConstructor
public class NodeStateRecorder {

    private final BrokerNodeRepository nodes;
    private final BrokerNodeMapper nodeMapper;
    private final HaStateEvaluator evaluator;

    /** Apply a tier-A HA read to one node, tagged with the cycle it was observed in. */
    @Transactional
    public void applyTierA(UUID nodeId, JsonNode ha, long cycle) {
        nodes.findById(nodeId).ifPresent(node -> {
            String state = evaluator.deriveState(boxedBool(ha, "Started"));
            String haRole = evaluator.deriveHaRole(boxedBool(ha, "Backup"), boxedBool(ha, "Clustered"));
            node.applyHaState(
                    new HaObservation(
                            bool(ha, "Active"),
                            state,
                            haRole,
                            boxedBool(ha, "ReplicaSync"),
                            text(ha, "Version"),
                            text(ha, "NodeID")),
                    cycle,
                    Instant.now());
        });
    }

    /** Record a failed scrape without disturbing last-known-good HA state. */
    @Transactional
    public void recordNodeError(UUID nodeId, String message) {
        nodes.findById(nodeId).ifPresent(node -> node.recordError(Instant.now(), message));
    }

    /**
     * Persist a corroboration pass: every node of the cluster takes the verdict of its NodeID, and
     * one the pass has no verdict for is not in split-brain. Written with the tier-A state so every
     * replica reads what the owner decided (ADR-0152).
     */
    @Transactional
    public void recordSplitBrain(UUID clusterId, Map<String, SplitBrainStatus> verdictsByNodeId) {
        for (BrokerNodeEntity node : nodes.findByClusterIdOrderByNameAsc(clusterId)) {
            String nodeId = node.getArtemisNodeId();
            node.recordSplitBrain(
                    nodeId == null
                            ? SplitBrainStatus.NONE
                            : verdictsByNodeId.getOrDefault(nodeId, SplitBrainStatus.NONE));
        }
    }

    /** Freshly persisted endpoints for a cluster — the input to split-brain corroboration. */
    @Transactional(readOnly = true)
    public List<NodeEndpoint> endpoints(UUID clusterId) {
        return nodeMapper.toEndpoints(nodes.findByClusterIdOrderByNameAsc(clusterId));
    }
}
