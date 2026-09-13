package io.github.sudoitir.artemisstudio.platform.scrape;

import static io.github.sudoitir.artemisstudio.platform.broker.JolokiaJson.bool;
import static io.github.sudoitir.artemisstudio.platform.broker.JolokiaJson.boxedBool;
import static io.github.sudoitir.artemisstudio.platform.broker.JolokiaJson.text;

import io.github.sudoitir.artemisstudio.platform.broker.NodeEndpoint;
import io.github.sudoitir.artemisstudio.platform.clusters.BrokerNodeMapper;
import io.github.sudoitir.artemisstudio.platform.clusters.BrokerNodeRepository;
import io.github.sudoitir.artemisstudio.platform.clusters.HaStateEvaluator;
import java.time.Instant;
import java.util.List;
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
public class ScrapePersistence {

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
                    bool(ha, "Active"),
                    state,
                    haRole,
                    boxedBool(ha, "ReplicaSync"),
                    cycle,
                    text(ha, "Version"),
                    text(ha, "NodeID"),
                    Instant.now());
        });
    }

    /** Record a failed scrape without disturbing last-known-good HA state. */
    @Transactional
    public void recordNodeError(UUID nodeId, String message) {
        nodes.findById(nodeId).ifPresent(node -> node.recordError(Instant.now(), message));
    }

    /** Freshly persisted endpoints for a cluster — the input to split-brain corroboration. */
    @Transactional(readOnly = true)
    public List<NodeEndpoint> endpoints(UUID clusterId) {
        return nodeMapper.toEndpoints(nodes.findByClusterIdOrderByNameAsc(clusterId));
    }
}
