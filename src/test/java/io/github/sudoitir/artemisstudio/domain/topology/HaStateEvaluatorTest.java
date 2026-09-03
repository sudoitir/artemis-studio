package io.github.sudoitir.artemisstudio.domain.topology;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.domain.topology.ClusterHealth.Level;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HaStateEvaluatorTest {

    private static final String NODE_ID = "f7734597-a768-11f1-aa4c-ceae3fa2df1d";
    private final HaStateEvaluator evaluator = new HaStateEvaluator();

    private static NodeEndpoint endpoint(
            String name, String role, boolean active, String state, Boolean replicaSync, Long cycle) {
        return new NodeEndpoint(
                UUID.randomUUID(),
                name,
                NODE_ID,
                "http://" + name + "/jolokia",
                name + ":61616",
                role,
                state,
                active,
                replicaSync,
                cycle,
                "2.44.0",
                null,
                Instant.now(),
                false,
                false,
                true);
    }

    @Test
    void deriveStateFromStarted() {
        assertThat(evaluator.deriveState(Boolean.TRUE)).isEqualTo("STARTED");
        assertThat(evaluator.deriveState(Boolean.FALSE)).isEqualTo("STOPPED");
        assertThat(evaluator.deriveState(null)).isEqualTo("UNKNOWN");
    }

    @Test
    void deriveHaRoleFromBackupThenClustered() {
        assertThat(evaluator.deriveHaRole(true, true)).isEqualTo("BACKUP");
        assertThat(evaluator.deriveHaRole(false, true)).isEqualTo("PRIMARY");
        assertThat(evaluator.deriveHaRole(false, false)).isEqualTo("STANDALONE");
    }

    @Test
    void healthyPairIsOkWithNoSplitBrain() {
        List<NodeEndpoint> eps = List.of(
                endpoint("primary", "PRIMARY", true, "STARTED", null, 5L),
                endpoint("backup", "BACKUP", false, "STARTED", true, 5L));

        List<LogicalNode> nodes = evaluator.toLogicalNodes(eps);
        ClusterHealth health = evaluator.toHealth(UUID.randomUUID(), nodes);

        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0).splitBrain()).isEqualTo(SplitBrainStatus.NONE);
        assertThat(health.level()).isEqualTo(Level.OK);
        assertThat(health.liveEndpointNames()).containsExactly("primary");
        assertThat(health.notes()).isEmpty();
    }

    @Test
    void healthyStandbyIsNotReportedDown() {
        // A synced backup is Started=true, Active=false. That must not read as a fault.
        List<NodeEndpoint> eps = List.of(
                endpoint("primary", "PRIMARY", true, "STARTED", null, 5L),
                endpoint("backup", "BACKUP", false, "STARTED", true, 5L));

        ClusterHealth health = evaluator.toHealth(UUID.randomUUID(), evaluator.toLogicalNodes(eps));

        assertThat(health.level()).isEqualTo(Level.OK);
    }

    @Test
    void replicationBehindIsDegraded() {
        List<NodeEndpoint> eps = List.of(
                endpoint("primary", "PRIMARY", true, "STARTED", null, 5L),
                endpoint("backup", "BACKUP", false, "STARTED", false, 5L));

        List<LogicalNode> nodes = evaluator.toLogicalNodes(eps);
        ClusterHealth health = evaluator.toHealth(UUID.randomUUID(), nodes);

        assertThat(nodes.get(0).replicationBehind()).isTrue();
        assertThat(health.replicationBehind()).isTrue();
        assertThat(health.level()).isEqualTo(Level.DEGRADED);
        assertThat(health.notes()).anyMatch(n -> n.contains("not caught up"));
    }

    @Test
    void splitBrainIsOnlySuspectedOnAFirstSameCycleSighting() {
        List<NodeEndpoint> eps = List.of(
                endpoint("primary", "PRIMARY", true, "STARTED", null, 7L),
                endpoint("backup", "BACKUP", true, "STARTED", null, 7L));

        SplitBrainStatus first = evaluator.evaluateSplitBrain(NODE_ID, eps);

        assertThat(first).isEqualTo(SplitBrainStatus.SUSPECTED);
    }

    @Test
    void splitBrainEscalatesToCriticalOnTheNextCycle() {
        List<NodeEndpoint> cycle7 = List.of(
                endpoint("primary", "PRIMARY", true, "STARTED", null, 7L),
                endpoint("backup", "BACKUP", true, "STARTED", null, 7L));
        List<NodeEndpoint> cycle8 = List.of(
                endpoint("primary", "PRIMARY", true, "STARTED", null, 8L),
                endpoint("backup", "BACKUP", true, "STARTED", null, 8L));

        assertThat(evaluator.evaluateSplitBrain(NODE_ID, cycle7)).isEqualTo(SplitBrainStatus.SUSPECTED);
        assertThat(evaluator.evaluateSplitBrain(NODE_ID, cycle8)).isEqualTo(SplitBrainStatus.CRITICAL);
    }

    @Test
    void plannedFailoverAcrossCyclesNeverEscalates() {
        // The false-alarm ADR-0012 exists to kill: two non-simultaneous reads, one
        // per cycle, both showing Active=true during a normal failover.
        List<NodeEndpoint> skewed = List.of(
                endpoint("primary", "PRIMARY", true, "STARTED", null, 7L),
                endpoint("backup", "BACKUP", true, "STARTED", null, 8L));

        assertThat(evaluator.evaluateSplitBrain(NODE_ID, skewed)).isEqualTo(SplitBrainStatus.NONE);
        assertThat(evaluator.evaluateSplitBrain(NODE_ID, skewed)).isEqualTo(SplitBrainStatus.NONE);
    }

    @Test
    void splitBrainClearsWhenOnlyOneNodeIsActiveAgain() {
        List<NodeEndpoint> dual = List.of(
                endpoint("primary", "PRIMARY", true, "STARTED", null, 7L),
                endpoint("backup", "BACKUP", true, "STARTED", null, 7L));
        List<NodeEndpoint> resolved = List.of(
                endpoint("primary", "PRIMARY", true, "STARTED", null, 8L),
                endpoint("backup", "BACKUP", false, "STARTED", true, 8L));

        assertThat(evaluator.evaluateSplitBrain(NODE_ID, dual)).isEqualTo(SplitBrainStatus.SUSPECTED);
        assertThat(evaluator.evaluateSplitBrain(NODE_ID, resolved)).isEqualTo(SplitBrainStatus.NONE);
        // And a later re-sighting starts over at SUSPECTED, not CRITICAL.
        assertThat(evaluator.evaluateSplitBrain(NODE_ID, dual)).isEqualTo(SplitBrainStatus.SUSPECTED);
    }

    @Test
    void aFailedOverPairDoesNotShowTheDeadPrimaryAsLive() {
        // The dead primary keeps a stale active=true, but it now carries a lastError.
        NodeEndpoint deadPrimary = new NodeEndpoint(
                UUID.randomUUID(),
                "primary",
                NODE_ID,
                "http://primary/jolokia",
                "primary:61616",
                "PRIMARY",
                "STARTED",
                true,
                null,
                6L,
                "2.44.0",
                "Nothing answered at this address.",
                Instant.now(),
                false,
                false,
                true);
        NodeEndpoint promotedBackup = endpoint("backup", "PRIMARY", true, "STARTED", true, 9L);

        List<LogicalNode> nodes = evaluator.toLogicalNodes(List.of(deadPrimary, promotedBackup));
        ClusterHealth health = evaluator.toHealth(UUID.randomUUID(), nodes);

        assertThat(nodes.get(0).splitBrain()).isEqualTo(SplitBrainStatus.NONE);
        assertThat(health.liveEndpointNames()).containsExactly("backup");
        assertThat(health.level()).isEqualTo(Level.DEGRADED);
        assertThat(health.notes()).anyMatch(n -> n.contains("unreachable"));
    }

    @Test
    void neverContactedClusterIsUnknown() {
        NodeEndpoint uncontacted = new NodeEndpoint(
                UUID.randomUUID(),
                "backup:61616",
                NODE_ID,
                null,
                "backup:61616",
                "BACKUP",
                "UNKNOWN",
                false,
                null,
                null,
                null,
                null,
                null,
                true,
                false,
                false);

        ClusterHealth health = evaluator.toHealth(UUID.randomUUID(), evaluator.toLogicalNodes(List.of(uncontacted)));

        assertThat(health.level()).isEqualTo(Level.UNKNOWN);
    }
}
