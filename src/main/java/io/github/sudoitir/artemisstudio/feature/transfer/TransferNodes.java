package io.github.sudoitir.artemisstudio.feature.transfer;

import io.github.sudoitir.artemisstudio.kernel.core.NotFoundException;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerConnectionException;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.platform.broker.CoreRelay;
import io.github.sudoitir.artemisstudio.platform.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterDirectory;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterNode;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The nodes a transfer talks to. A run names each side by the broker's own node id, shared by a
 * live/backup pair, and resolves the endpoint serving it at each batch, so a failover continues on the
 * new live node.
 */
@Component
@RequiredArgsConstructor
class TransferNodes {

    private final ClusterDirectory directory;
    private final BrokerConnections connections;

    /** A node of this cluster by Studio's id; 404 when it is not one. */
    ClusterNode node(UUID clusterId, UUID nodeId) {
        return directory
                .node(nodeId)
                .filter(n -> n.getClusterId().equals(clusterId))
                .orElseThrow(() -> new NotFoundException("node", nodeId));
    }

    static boolean live(ClusterNode node) {
        return Boolean.TRUE.equals(node.getActive()) && node.getLastError() == null;
    }

    static boolean backup(ClusterNode node) {
        return "BACKUP".equals(node.getHaRole());
    }

    /** The manageable endpoint live now for this broker node id. */
    ClusterNode serving(UUID clusterId, String artemisNodeId, String name) {
        return directory.nodes(clusterId).stream()
                .filter(n -> artemisNodeId.equals(n.getArtemisNodeId()))
                .filter(n -> n.getJolokiaUrl() != null && live(n) && !backup(n))
                .findFirst()
                .orElseThrow(() -> new BrokerConnectionException(
                        BrokerConnectionException.Kind.UNREACHABLE,
                        "Node " + name + " has no live, manageable endpoint now."));
    }

    JolokiaBrokerClient client(ClusterNode node) {
        return connections.forCluster(node.getClusterId(), node.getJolokiaUrl());
    }

    CoreRelay.Endpoint core(ClusterNode node) {
        if (node.getCoreUrl() == null) {
            throw new BrokerConnectionException(
                    BrokerConnectionException.Kind.UNREACHABLE,
                    "Node " + node.getName() + " has no Core URL, so Studio cannot relay messages to or from it.");
        }
        return new CoreRelay.Endpoint(
                node.getClusterId(), node.getCoreUrl(), connections.coreSettingsFor(node.getClusterId()));
    }
}
