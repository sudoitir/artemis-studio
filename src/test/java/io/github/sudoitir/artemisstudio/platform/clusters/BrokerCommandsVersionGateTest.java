package io.github.sudoitir.artemisstudio.platform.clusters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.kernel.audit.AuditService;
import io.github.sudoitir.artemisstudio.kernel.security.ActorResolver;
import io.github.sudoitir.artemisstudio.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsService;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerVersion;
import io.github.sudoitir.artemisstudio.platform.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.platform.broker.VersionGate;
import io.github.sudoitir.artemisstudio.platform.clusters.BrokerCommands.Command;
import io.github.sudoitir.artemisstudio.platform.clusters.LifecycleOutcome.NodeOutcome;
import io.github.sudoitir.artemisstudio.platform.clusters.LifecycleOutcome.NodeStatus;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.BrokerNodeRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A mixed-version cluster keeps a gated command for the nodes that have it (ADR-0142). */
class BrokerCommandsVersionGateTest {

    private static final UUID CLUSTER = UUID.randomUUID();
    private static final VersionGate GATE = new VersionGate("X", "Doing X", new BrokerVersion(2, 38, 0));

    private final BrokerNodeRepository nodes = mock(BrokerNodeRepository.class);
    private final BrokerConnections connections = mock(BrokerConnections.class);
    private final BrokerCommands commands = new BrokerCommands(
            nodes,
            connections,
            mock(AuditService.class),
            mock(ActorResolver.class),
            mock(SettingsService.class),
            mock(ClusterAccessGuard.class),
            mock(CapabilityLedger.class));

    private static BrokerNodeEntity node(String name, String version) {
        BrokerNodeEntity n = mock(BrokerNodeEntity.class);
        when(n.getId()).thenReturn(UUID.randomUUID());
        when(n.getName()).thenReturn(name);
        when(n.getArtemisNodeId()).thenReturn(name);
        when(n.getJolokiaUrl()).thenReturn("http://" + name + ":8161/console/jolokia");
        when(n.getActive()).thenReturn(true);
        when(n.getVersion()).thenReturn(version);
        return n;
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void anOlderNodeIsSkippedWithTheReleaseItNeedsAndTheOthersProceed(boolean dryRun) {
        List<BrokerNodeEntity> cluster = List.of(node("new", "2.57.0"), node("old", "2.35.0"), node("unknown", null));
        when(nodes.findByClusterIdOrderByNameAsc(CLUSTER)).thenReturn(cluster);
        when(connections.forCluster(any(), any())).thenReturn(mock(JolokiaBrokerClient.class));

        LifecycleOutcome outcome = commands.run(Command.builder()
                .clusterId(CLUSTER)
                .permission("x:write")
                .auditAction("X")
                .targetType("X")
                .targetName("x")
                .dryRun(dryRun)
                .requires(GATE)
                .action((client, broker) -> NodeStatus.APPLIED)
                .build());

        NodeStatus proceeded = dryRun ? NodeStatus.WOULD_APPLY : NodeStatus.APPLIED;
        assertThat(outcome.nodes())
                .extracting(NodeOutcome::nodeName, NodeOutcome::status)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("new", proceeded),
                        org.assertj.core.groups.Tuple.tuple("old", NodeStatus.UNSUPPORTED_VERSION),
                        org.assertj.core.groups.Tuple.tuple("unknown", proceeded));
        assertThat(outcome.nodes().get(1).error())
                .contains("Doing X needs Artemis 2.38.0")
                .contains("2.35.0");
        assertThat(outcome.anyFailed()).isFalse();
    }

    @Test
    void aCommandWithoutAGateReachesEveryNode() {
        List<BrokerNodeEntity> cluster = List.of(node("old", "2.32.0"));
        when(nodes.findByClusterIdOrderByNameAsc(CLUSTER)).thenReturn(cluster);
        when(connections.forCluster(any(), any())).thenReturn(mock(JolokiaBrokerClient.class));

        LifecycleOutcome outcome = commands.run(Command.builder()
                .clusterId(CLUSTER)
                .permission("x:write")
                .auditAction("X")
                .targetType("X")
                .targetName("x")
                .action((client, broker) -> NodeStatus.APPLIED)
                .build());

        assertThat(outcome.nodes()).extracting(NodeOutcome::status).containsExactly(NodeStatus.APPLIED);
    }
}
