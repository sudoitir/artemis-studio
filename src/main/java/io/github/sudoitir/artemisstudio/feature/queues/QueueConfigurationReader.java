package io.github.sudoitir.artemisstudio.feature.queues;

import io.github.sudoitir.artemisstudio.kernel.core.NotFoundException;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerConnectionException;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerMBeans;
import io.github.sudoitir.artemisstudio.platform.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterDirectory;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterNode;
import io.github.sudoitir.artemisstudio.platform.scrape.QueueLocator;
import io.github.sudoitir.artemisstudio.platform.scrape.QueueLocator.QueueLocation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * What a queue is configured as, on each node that has it.
 *
 * <p>An update replaces the broker's whole configuration rather than merging into
 * it, so the edit form has to show what is there before an operator changes one
 * field of it: a form of blank inputs cannot say what the queue runs, and after an
 * update it cannot say whether the update took. This is the read that
 * {@link QueueLifecycleOperations#updateQueue} does for itself, exposed so the
 * screen can do it first.
 *
 * <p>One read per node that has the queue, and a node that cannot be read says so
 * rather than dropping out of the list — an absent node reads as a queue that is
 * not there.
 */
@Component
@RequiredArgsConstructor
public class QueueConfigurationReader {

    private final QueueLocator queueLocator;
    private final ClusterDirectory clusters;
    private final BrokerConnections connections;
    private final QueueLifecycleOperations ops;

    /** One node's copy of the queue: its configuration, or why it could not be read. */
    public record NodeConfiguration(
            UUID nodeId, String nodeName, Map<String, Object> values, String unavailableReason) {}

    /** Every node's copy, with the address and routing type the queue is bound as. */
    public record QueueConfiguration(
            String queueName, String address, String routingType, List<NodeConfiguration> nodes) {}

    public QueueConfiguration read(UUID clusterId, String queueName) {
        List<QueueLocation> locations = queueLocator.locate(clusterId, queueName);
        if (locations.isEmpty()) {
            throw new NotFoundException("queue", queueName);
        }
        List<ClusterNode> nodes = clusters.nodes(clusterId);
        List<NodeConfiguration> read = new ArrayList<>();
        for (QueueLocation location : locations) {
            ClusterNode node = nodes.stream()
                    .filter(n -> n.getId().equals(location.nodeId()))
                    .findFirst()
                    .orElse(null);
            if (node == null) {
                continue;
            }
            try {
                JolokiaBrokerClient client = connections.forCluster(clusterId, node.getJolokiaUrl());
                String mbean = BrokerMBeans.queue(
                        client.resolveBrokerObjectName(),
                        location.address(),
                        location.queueName(),
                        location.routingType());
                read.add(new NodeConfiguration(node.getId(), node.getName(), ops.readQueueConfig(client, mbean), null));
            } catch (BrokerConnectionException e) {
                read.add(new NodeConfiguration(node.getId(), node.getName(), Map.of(), e.getMessage()));
            }
        }
        QueueLocation first = locations.getFirst();
        return new QueueConfiguration(queueName, first.address(), first.routingType(), read);
    }
}
