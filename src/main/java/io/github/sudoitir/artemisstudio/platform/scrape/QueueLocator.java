package io.github.sudoitir.artemisstudio.platform.scrape;

import io.github.sudoitir.artemisstudio.platform.broker.BrokerConnectionException;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerMBeans;
import io.github.sudoitir.artemisstudio.platform.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterDirectory;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterNode;
import io.github.sudoitir.artemisstudio.platform.clusters.ServingNodes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Where a queue lives in a cluster: its address and routing type on each node that has it.
 *
 * <p>The scraped snapshot answers first. A queue the scrape has not reached yet — created a
 * moment ago, or on a node whose sweep has not come round — is looked up live, with one JMX
 * search per live node, so an operator acting on a queue they can see on the broker is not
 * told it does not exist.
 */
@Component
@RequiredArgsConstructor
public class QueueLocator {

    private final QueueSnapshots snapshots;
    private final ClusterDirectory clusters;
    private final BrokerConnections connections;

    /** One node's copy of a queue. {@code routingType} is upper case, as the snapshot stores it. */
    public record QueueLocation(UUID nodeId, String queueName, String address, String routingType) {}

    /** Every node's copy of the queue; empty when no node has it. */
    public List<QueueLocation> locate(UUID clusterId, String queueName) {
        List<QueueLocation> cached = snapshots.forCluster(clusterId).stream()
                .filter(s -> s.queueName().equals(queueName))
                .map(s -> new QueueLocation(s.nodeId(), queueName, s.address(), s.routingType()))
                .toList();
        return cached.isEmpty() ? live(clusterId, queueName) : cached;
    }

    private List<QueueLocation> live(UUID clusterId, String queueName) {
        List<QueueLocation> found = new ArrayList<>();
        for (ClusterNode node : ServingNodes.from(clusters.nodes(clusterId))) {
            if (!Boolean.TRUE.equals(node.getActive())) {
                continue;
            }
            try {
                JolokiaBrokerClient client = connections.forCluster(clusterId, node.getJolokiaUrl());
                for (String name :
                        client.search(BrokerMBeans.queuePattern(client.resolveBrokerObjectName(), queueName))) {
                    ObjectName mbean = new ObjectName(name);
                    found.add(new QueueLocation(
                            node.getId(),
                            queueName,
                            value(mbean, "address"),
                            value(mbean, "routing-type").toUpperCase(Locale.ROOT)));
                }
            } catch (BrokerConnectionException | MalformedObjectNameException e) {
                // A node that cannot be searched has not shown the queue; the others still answer.
            }
        }
        return found;
    }

    /** Artemis quotes these values; an unquoted one is taken as it is. */
    private static String value(ObjectName mbean, String key) {
        String raw = mbean.getKeyProperty(key);
        return raw.startsWith("\"") ? ObjectName.unquote(raw) : raw;
    }
}
