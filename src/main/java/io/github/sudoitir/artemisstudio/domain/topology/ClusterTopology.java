package io.github.sudoitir.artemisstudio.domain.topology;

import java.util.List;
import java.util.UUID;

/**
 * The current cross-node view of one cluster: its logical nodes, each with a
 * serving side and (usually) a replica side.
 */
public record ClusterTopology(UUID clusterId, List<LogicalNode> nodes) {

    /** Endpoints that were discovered by report but never contacted — "found, not yet manageable". */
    public List<NodeEndpoint> unmanaged() {
        return nodes.stream()
                .flatMap(n -> n.endpoints().stream())
                .filter(e -> !e.manageable())
                .toList();
    }
}
