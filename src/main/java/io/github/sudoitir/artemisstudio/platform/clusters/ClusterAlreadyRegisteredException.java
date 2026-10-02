package io.github.sudoitir.artemisstudio.platform.clusters;

import java.util.List;
import java.util.UUID;

/**
 * A registration, or its connection check, over brokers a registered cluster already holds (ADR-0167).
 * Rendered as a 409 that names the cluster and the overlapping nodes when the caller may read that
 * cluster; otherwise it only says the brokers are taken, since naming a cluster to someone with no grant
 * on it would reveal that it exists: then the id and name are {@code null} and no node is listed.
 */
public class ClusterAlreadyRegisteredException extends RuntimeException {

    /**
     * A broker copied from another (a cloned VM, a restored backup of its data directory) carries the same
     * NodeID, so Studio cannot tell it apart from the one it was copied from (ADR-0167).
     */
    private static final String CLONED = " If this is a cloned or restored broker, it carries the same node ID;"
            + " give it a fresh journal so it gets its own.";

    private final UUID existingClusterId;
    private final String existingClusterName;
    private final List<String> overlappingNodes;

    ClusterAlreadyRegisteredException(UUID existingClusterId, String existingClusterName, List<String> nodes) {
        super(detail(existingClusterId, existingClusterName, nodes));
        this.existingClusterId = existingClusterId;
        this.existingClusterName = existingClusterName;
        this.overlappingNodes = List.copyOf(nodes);
    }

    /** A cluster the caller may not read: nothing about it is named. */
    static ClusterAlreadyRegisteredException hidden() {
        return new ClusterAlreadyRegisteredException(null, null, List.of());
    }

    public UUID existingClusterId() {
        return existingClusterId;
    }

    public String existingClusterName() {
        return existingClusterName;
    }

    public List<String> overlappingNodes() {
        return overlappingNodes;
    }

    private static String detail(UUID clusterId, String name, List<String> nodes) {
        if (clusterId == null) {
            return "These brokers already belong to a registered cluster you do not have access to."
                    + " Ask someone who can see it to share it with you, or to remove it." + CLONED;
        }
        String which =
                switch (nodes.size()) {
                    case 0 -> "";
                    case 1 -> " (node " + nodes.get(0) + ")";
                    default -> " (nodes " + String.join(", ", nodes) + ")";
                };
        return "These brokers are already registered as the cluster \"" + name + "\"" + which + "."
                + " Open that cluster instead, or remove it before registering them again." + CLONED;
    }
}
