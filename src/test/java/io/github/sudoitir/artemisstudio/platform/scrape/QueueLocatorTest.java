package io.github.sudoitir.artemisstudio.platform.scrape;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.platform.broker.BrokerConnectionException;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterDirectory;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QueueLocatorTest {

    private final UUID clusterId = UUID.randomUUID();
    private final ClusterDirectory clusters = mock(ClusterDirectory.class);
    private final BrokerConnections connections = mock(BrokerConnections.class);
    private final QueueSnapshots snapshots = mock(QueueSnapshots.class);

    @Test
    void aNodeThatCannotBeReadIsReportedNotTakenForAMissingQueue() {
        ClusterNode node = node();
        when(clusters.nodes(clusterId)).thenReturn(List.of(node));
        when(snapshots.forCluster(clusterId)).thenReturn(List.of());
        when(connections.forCluster(any(), any()))
                .thenThrow(new BrokerConnectionException(BrokerConnectionException.Kind.THROTTLED, "held back"));

        QueueLocator locator = new QueueLocator(snapshots, clusters, connections);

        assertThatThrownBy(() -> locator.locate(clusterId, "orders"))
                .isInstanceOf(BrokerConnectionException.class)
                .hasMessage("held back");
    }

    @Test
    void noActiveNodeMeansNoLocation() {
        ClusterNode node = node();
        when(node.getActive()).thenReturn(false);
        when(clusters.nodes(clusterId)).thenReturn(List.of(node));
        when(snapshots.forCluster(clusterId)).thenReturn(List.of());

        assertThat(new QueueLocator(snapshots, clusters, connections).locate(clusterId, "orders"))
                .isEmpty();
    }

    private static ClusterNode node() {
        ClusterNode node = mock(ClusterNode.class);
        when(node.getId()).thenReturn(UUID.randomUUID());
        when(node.getJolokiaUrl()).thenReturn("http://broker:8161/console/jolokia");
        when(node.getActive()).thenReturn(true);
        return node;
    }
}
