package io.github.sudoitir.artemisstudio.scheduler;

import static io.github.sudoitir.artemisstudio.broker.JolokiaJson.bool;
import static io.github.sudoitir.artemisstudio.broker.JolokiaJson.boxedBool;
import static io.github.sudoitir.artemisstudio.broker.JolokiaJson.text;

import io.github.sudoitir.artemisstudio.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.domain.topology.HaStateEvaluator;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeRepository;
import io.github.sudoitir.artemisstudio.persist.ClusterEntity;
import io.github.sudoitir.artemisstudio.persist.ClusterRepository;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * The Phase 1 refresh loop: one batched Jolokia read per manageable node per
 * tier-A tick, updating {@code broker_node} HA state tagged with a monotonic
 * cycle number (ADR-0012 needs "same cycle" to be checkable).
 *
 * <p>A node's failure is captured onto {@code last_error} and never propagated —
 * one unreachable broker must not stop the others from refreshing.
 *
 * <p>Phase 2 deletes this class whole and replaces it with the tiered scheduler
 * plus a per-node token bucket (ADR-0002 non-negotiable #1).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HaRefreshTask {

    private static final String[] HA_ATTRS = {
        "Active", "Started", "Backup", "ReplicaSync", "NodeID", "Clustered", "Version"
    };

    private final ClusterRepository clusters;
    private final BrokerNodeRepository nodes;
    private final BrokerConnections connections;
    private final HaStateEvaluator evaluator;

    private final AtomicLong cycleCounter = new AtomicLong();

    @Scheduled(
            fixedDelayString = "${artemis-studio.scrape.tier-a-interval}",
            initialDelayString = "${artemis-studio.scrape.tier-a-interval}")
    @Transactional
    public void refresh() {
        long cycle = cycleCounter.incrementAndGet();
        for (ClusterEntity cluster : clusters.findAll()) {
            for (BrokerNodeEntity node : nodes.findByClusterIdOrderByNameAsc(cluster.getId())) {
                if (node.getJolokiaUrl() != null) {
                    refreshNode(cluster.getId(), node, cycle);
                }
            }
        }
    }

    /** Refresh one node. Never throws — a failure lands on {@code last_error}. */
    void refreshNode(UUID clusterId, BrokerNodeEntity node, long cycle) {
        try {
            JolokiaBrokerClient client = connections.forCluster(clusterId, node.getJolokiaUrl());
            JsonNode ha = client.readBrokerAttributes(HA_ATTRS).value();

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
        } catch (RuntimeException e) {
            String message =
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("HA refresh failed for {} ({}): {}", node.getName(), node.getJolokiaUrl(), message);
            node.recordError(Instant.now(), message);
        }
    }
}
