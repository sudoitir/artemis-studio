package io.github.sudoitir.artemisstudio.platform.clusters;

import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.ClusterRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registered clusters and their nodes, as other modules read them (ADR-0069). Read-only:
 * registration changes nodes, and {@link NodeStateRecorder} records what a scrape observed.
 */
@Component
@RequiredArgsConstructor
public class ClusterDirectory {

    private final ClusterRepository clusters;
    private final BrokerNodeRepository nodes;

    /** A cluster's nodes, ordered by name. */
    @Transactional(readOnly = true)
    public List<ClusterNode> nodes(UUID clusterId) {
        return List.copyOf(nodes.findByClusterIdOrderByNameAsc(clusterId));
    }

    @Transactional(readOnly = true)
    public List<ClusterNode> allNodes() {
        return List.copyOf(nodes.findAll());
    }

    @Transactional(readOnly = true)
    public Optional<ClusterNode> node(UUID nodeId) {
        return nodes.findById(nodeId).map(ClusterNode.class::cast);
    }

    /** Every registered cluster, ordered by name. */
    @Transactional(readOnly = true)
    public List<RegisteredCluster> clusters() {
        return List.copyOf(clusters.findAllByOrderByNameAsc());
    }

    @Transactional(readOnly = true)
    public Optional<RegisteredCluster> cluster(UUID clusterId) {
        return clusters.findById(clusterId).map(RegisteredCluster.class::cast);
    }
}
