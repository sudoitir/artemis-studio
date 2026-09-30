package io.github.sudoitir.artemisstudio.platform.scrape;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import io.github.sudoitir.artemisstudio.platform.broker.NodeEndpoint;
import io.github.sudoitir.artemisstudio.platform.clusters.NodeStateRecorder;
import io.github.sudoitir.artemisstudio.platform.clusters.SplitBrainStatus;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The ADR-0012 corroboration ratchet, now owned by {@link ScrapeCycle} (moved
 * verbatim from {@code HaStateEvaluator}). Covers first-sight vs confirmed vs the
 * planned-failover guard, and per-cluster isolation of the counter.
 */
class ScrapeCycleTest {

    private static final String NODE_ID = "f7734597-a768-11f1-aa4c-ceae3fa2df1d";

    /** The verdicts {@link ScrapeCycle} persisted, per cluster, as the node rows would hold them. */
    private final Map<UUID, Map<String, SplitBrainStatus>> persisted = new HashMap<>();

    private final NodeStateRecorder recorder = mock(NodeStateRecorder.class);
    private final ScrapeCycle cycle = new ScrapeCycle(recorder);

    @BeforeEach
    void record() {
        doAnswer(invocation -> persisted.put(invocation.getArgument(0), invocation.getArgument(1)))
                .when(recorder)
                .recordSplitBrain(any(), any());
    }

    private SplitBrainStatus statusFor(UUID clusterId) {
        return persisted.getOrDefault(clusterId, Map.of()).getOrDefault(NODE_ID, SplitBrainStatus.NONE);
    }

    private static NodeEndpoint endpoint(String name, boolean active, Long observedCycle) {
        return new NodeEndpoint(
                UUID.randomUUID(),
                name,
                NODE_ID,
                "http://" + name + "/jolokia",
                name + ":61616",
                active ? "PRIMARY" : "BACKUP",
                "STARTED",
                active,
                null,
                observedCycle,
                "2.44.0",
                null,
                Instant.now(),
                false,
                false,
                true);
    }

    private SplitBrainStatus corroborate(UUID clusterId, NodeEndpoint... endpoints) {
        cycle.corroborate(clusterId, List.of(endpoints));
        return statusFor(clusterId);
    }

    @Test
    void cycleNumberIsPerClusterAndMonotonic() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        assertThat(cycle.next(a)).isEqualTo(1L);
        assertThat(cycle.next(a)).isEqualTo(2L);
        assertThat(cycle.next(b)).isEqualTo(1L);
        assertThat(cycle.current(a)).isEqualTo(2L);
        assertThat(cycle.current(b)).isEqualTo(1L);
    }

    @Test
    void firstSameCycleDualActiveIsOnlySuspected() {
        UUID clusterId = UUID.randomUUID();

        SplitBrainStatus status = corroborate(clusterId, endpoint("primary", true, 7L), endpoint("backup", true, 7L));

        assertThat(status).isEqualTo(SplitBrainStatus.SUSPECTED);
    }

    @Test
    void escalatesToCriticalOnTheNextConsecutiveCycle() {
        UUID clusterId = UUID.randomUUID();

        assertThat(corroborate(clusterId, endpoint("primary", true, 7L), endpoint("backup", true, 7L)))
                .isEqualTo(SplitBrainStatus.SUSPECTED);
        assertThat(corroborate(clusterId, endpoint("primary", true, 8L), endpoint("backup", true, 8L)))
                .isEqualTo(SplitBrainStatus.CRITICAL);
    }

    @Test
    void plannedFailoverReadingsFromDifferentCyclesNeverEscalate() {
        UUID clusterId = UUID.randomUUID();

        assertThat(corroborate(clusterId, endpoint("primary", true, 7L), endpoint("backup", true, 8L)))
                .isEqualTo(SplitBrainStatus.NONE);
        assertThat(corroborate(clusterId, endpoint("primary", true, 8L), endpoint("backup", true, 9L)))
                .isEqualTo(SplitBrainStatus.NONE);
    }

    @Test
    void clearsAndRestartsAtSuspectedWhenBackToSingleActive() {
        UUID clusterId = UUID.randomUUID();

        assertThat(corroborate(clusterId, endpoint("primary", true, 7L), endpoint("backup", true, 7L)))
                .isEqualTo(SplitBrainStatus.SUSPECTED);
        assertThat(corroborate(clusterId, endpoint("primary", true, 8L), endpoint("backup", false, 8L)))
                .isEqualTo(SplitBrainStatus.NONE);
        assertThat(corroborate(clusterId, endpoint("primary", true, 9L), endpoint("backup", true, 9L)))
                .isEqualTo(SplitBrainStatus.SUSPECTED);
    }

    @Test
    void oneClusterInSplitBrainDoesNotAffectAnother() {
        UUID split = UUID.randomUUID();
        UUID healthy = UUID.randomUUID();

        corroborate(split, endpoint("primary", true, 7L), endpoint("backup", true, 7L));
        corroborate(split, endpoint("primary", true, 8L), endpoint("backup", true, 8L));
        corroborate(healthy, endpoint("primary", true, 8L), endpoint("backup", false, 8L));

        assertThat(statusFor(split)).isEqualTo(SplitBrainStatus.CRITICAL);
        assertThat(statusFor(healthy)).isEqualTo(SplitBrainStatus.NONE);
    }

    @Test
    void forgetRestartsTheCorroborationRatchetOfACluster() {
        UUID clusterId = UUID.randomUUID();
        cycle.next(clusterId);
        corroborate(clusterId, endpoint("primary", true, 1L), endpoint("backup", true, 1L));

        cycle.forget(clusterId);

        assertThat(cycle.current(clusterId)).isZero();
        corroborate(clusterId, endpoint("primary", true, 2L), endpoint("backup", true, 2L));
        assertThat(statusFor(clusterId))
                .as("the ratchet starts again, so the first sighting is not yet critical")
                .isEqualTo(SplitBrainStatus.SUSPECTED);
    }
}
