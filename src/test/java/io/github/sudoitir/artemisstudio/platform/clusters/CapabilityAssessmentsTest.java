package io.github.sudoitir.artemisstudio.platform.clusters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.kernel.replica.ReplicaSignal;
import io.github.sudoitir.artemisstudio.kernel.replica.StudioBus;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerCapabilities;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerCapabilities.CapabilityAssessment;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerConnectionException;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.platform.broker.CapabilityProbe;
import io.github.sudoitir.artemisstudio.platform.broker.CoreSubscriptionManager;
import io.github.sudoitir.artemisstudio.platform.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.BrokerNodeRepository;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class CapabilityAssessmentsTest {

    private static final String URL = "http://broker/console/jolokia";

    private final UUID clusterId = UUID.randomUUID();
    private final BrokerNodeRepository nodes = mock(BrokerNodeRepository.class);
    private final BrokerConnections connections = mock(BrokerConnections.class);
    private final CapabilityProbe probe = mock(CapabilityProbe.class);
    private final StudioBus bus = mock(StudioBus.class);
    private final CapabilityAssessments assessments = new CapabilityAssessments(
            nodes, connections, probe, mock(CapabilityLedger.class), mock(CoreSubscriptionManager.class), bus);
    private final BrokerCapabilities result = new BrokerCapabilities(
            CapabilityAssessment.available("read"),
            CapabilityAssessment.unknown("write", null),
            CapabilityAssessment.unknown("n", null),
            CapabilityAssessment.unknown("m", null),
            CapabilityAssessment.unknown("s", null));

    CapabilityAssessmentsTest() {
        BrokerNodeEntity node = mock(BrokerNodeEntity.class);
        when(node.getJolokiaUrl()).thenReturn(URL);
        when(nodes.findByClusterIdOrderByNameAsc(clusterId)).thenReturn(List.of(node));
        when(connections.forCluster(any(), any())).thenReturn(mock(JolokiaBrokerClient.class));
    }

    @Test
    void concurrentReadersShareOneProbeThatRunsOutsideAnyTransaction() throws Exception {
        AtomicInteger probes = new AtomicInteger();
        AtomicBoolean inTransaction = new AtomicBoolean();
        CountDownLatch release = new CountDownLatch(1);
        when(probe.probe(any(), any(), any())).thenAnswer(call -> {
            probes.incrementAndGet();
            inTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            release.await(5, TimeUnit.SECONDS);
            return result;
        });

        try (ExecutorService readers = Executors.newFixedThreadPool(20)) {
            List<Future<CapabilityAssessments.Outcome>> reads = java.util.stream.IntStream.range(0, 20)
                    .mapToObj(i -> readers.submit(() -> assessments.outcome(clusterId)))
                    .toList();
            // every reader is waiting on the one probe before it is released
            await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(5)).until(() -> probes.get() == 1);
            release.countDown();
            for (Future<CapabilityAssessments.Outcome> read : reads) {
                assertThat(read.get(5, TimeUnit.SECONDS).orThrow()).isSameAs(result);
            }
        }

        assertThat(probes).hasValue(1);
        assertThat(inTransaction).isFalse();
        assertThat(assessments.outcome(clusterId).orThrow()).isSameAs(result);
        assertThat(probes).hasValue(1);
    }

    @Test
    void anUnreachableBrokerIsAskedOncePerWindowAndTheFailureIsKept() {
        AtomicInteger probes = new AtomicInteger();
        when(probe.probe(any(), any(), any())).thenAnswer(call -> {
            probes.incrementAndGet();
            throw new BrokerConnectionException(BrokerConnectionException.Kind.UNREACHABLE, "down");
        });

        CapabilityAssessments.Outcome first = assessments.outcome(clusterId);
        assertThatThrownBy(first::orThrow).isInstanceOf(BrokerConnectionException.class);
        CapabilityAssessments.Outcome second = assessments.outcome(clusterId);
        assertThatThrownBy(second::orThrow).isInstanceOf(BrokerConnectionException.class);

        assertThat(probes).hasValue(1);
    }

    @Test
    void invalidatingProbesAgainAndTellsTheOtherReplicas() {
        when(probe.probe(any(), any(), any())).thenReturn(result);
        assessments.outcome(clusterId);

        assessments.invalidate(clusterId);
        assessments.outcome(clusterId);

        verify(probe, org.mockito.Mockito.times(2)).probe(any(), any(), any());
        verify(bus).publish(new ReplicaSignal("capability-assessment", clusterId.toString()));
    }

    @Test
    void aSignalFromAnotherReplicaDropsTheEntry() {
        when(probe.probe(any(), any(), any())).thenReturn(result);
        assessments.outcome(clusterId);

        assessments.onSignal(new ReplicaSignal("capability-assessment", clusterId.toString()));
        assessments.outcome(clusterId);

        verify(probe, org.mockito.Mockito.times(2)).probe(any(), any(), any());
    }
}
