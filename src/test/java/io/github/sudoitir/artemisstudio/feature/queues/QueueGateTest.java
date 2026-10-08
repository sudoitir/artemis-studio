package io.github.sudoitir.artemisstudio.feature.queues;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.feature.queues.QueueLifecycleService.AddressDeleteParams;
import io.github.sudoitir.artemisstudio.feature.queues.QueueLifecycleService.QueueDeleteParams;
import io.github.sudoitir.artemisstudio.feature.queues.QueueLifecycleService.Reach;
import io.github.sudoitir.artemisstudio.kernel.core.NotFoundException;
import io.github.sudoitir.artemisstudio.kernel.gate.Effect;
import io.github.sudoitir.artemisstudio.kernel.gate.GatedOperation;
import io.github.sudoitir.artemisstudio.kernel.gate.Operation;
import io.github.sudoitir.artemisstudio.kernel.gate.OperationGate;
import io.github.sudoitir.artemisstudio.kernel.gate.OperationHeldException;
import io.github.sudoitir.artemisstudio.kernel.gate.Trait;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.platform.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.platform.broker.JolokiaResponse;
import io.github.sudoitir.artemisstudio.platform.broker.QueueRow;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.BrokerNodeEntity.HaObservation;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.artemisstudio.platform.scrape.QueueSnapshotUpsert;
import io.github.sudoitir.artemisstudio.support.AdminAuthenticationExtension;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Queue and address deletes through the approval gate (ADR-0179): authorized first, a dry run never gated, a real run
 * gated as {@code queue.delete} or {@code address.delete}, held without a broker call, estimated from the scrape and
 * pinned to the queue's identity. The gate is a stand-in; the engine's own tests prove what it does with each answer.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class QueueGateTest extends PostgresIntegrationTest {

    private static final String QUEUE = "orders";

    @Autowired
    QueueLifecycleService lifecycle;

    @Autowired
    GatedOperation<QueueDeleteParams> queueDeleteOperation;

    @Autowired
    GatedOperation<AddressDeleteParams> addressDeleteOperation;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerNodeRepository nodes;

    @Autowired
    QueueSnapshotUpsert upsert;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    OperationGate gate;

    @MockitoBean
    BrokerConnections connections;

    @MockitoBean
    QueueLifecycleOperations ops;

    @MockitoBean
    DivertOperations divertOps;

    @MockitoBean
    DeclaredDiverts declaredDiverts;

    @MockitoBean
    CaptureTaps captureTaps;

    private UUID clusterId;
    private UUID nodeId;

    @BeforeEach
    void seed() {
        clusterId = clusters.save(new ClusterEntity("c-" + UUID.randomUUID(), null, null))
                .getId();
        BrokerNodeEntity node = BrokerNodeEntity.fromSeed(
                clusterId, "live", "PRIMARY", UUID.randomUUID().toString());
        node.attachSeedUrl("http://live:8161/console/jolokia");
        node.applyHaState(new HaObservation(true, "STARTED", "PRIMARY", null, "2.44.0", null), 1L, Instant.now());
        nodeId = nodes.save(node).getId();
        JolokiaBrokerClient client = org.mockito.Mockito.mock(JolokiaBrokerClient.class);
        when(client.resolveBrokerObjectName()).thenReturn("org.apache.activemq.artemis:broker=\"b\"");
        when(connections.forCluster(eq(clusterId), anyString())).thenReturn(client);
        // No node has a queue the scrape does not show.
        when(client.single(any())).thenReturn(new JolokiaResponse(404, null, "no such queue", null, null));
        when(ops.deleteState(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new QueueLifecycleOperations.DeleteState(false, 0, List.of()));
        upsert.upsertBatch(List.of(
                new QueueRow(clusterId, nodeId, "orders.addr", QUEUE, "ANYCAST", true, 5, 0, 0, 0, 0, 0, 0, false)));
    }

    @AfterEach
    void cleanUp() {
        clusters.deleteById(clusterId);
    }

    private void holds() {
        doThrow(new OperationHeldException(
                        UUID.randomUUID(), "Delete queue orders", Instant.now().plus(Duration.ofHours(1))))
                .when(gate)
                .run(any(), any());
    }

    @Test
    void aDryRunIsNeverGated() {
        lifecycle.deleteQueue(clusterId, QUEUE, true, false, false);
        lifecycle.deleteAddress(clusterId, "orders.addr", true);

        verify(gate, never()).run(any(), any());
    }

    @Test
    void aRealQueueDeletePassesTheGateWithItsParameters() {
        holds();

        assertThatThrownBy(() -> lifecycle.deleteQueue(clusterId, QUEUE, false, true, true))
                .isInstanceOf(OperationHeldException.class);

        ArgumentCaptor<Operation> operation = ArgumentCaptor.forClass(Operation.class);
        verify(gate).run(operation.capture(), any());
        assertThat(operation.getValue().params()).isEqualTo(new QueueDeleteParams(clusterId, QUEUE, true, true));
    }

    @Test
    void aHeldQueueDeleteTouchesNoBroker() {
        holds();

        assertThatThrownBy(() -> lifecycle.deleteQueue(clusterId, QUEUE, false, false, false))
                .isInstanceOf(OperationHeldException.class);

        verify(ops, never()).destroyQueue(any(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void aRealAddressDeletePassesTheGateWithItsParameters() {
        holds();

        assertThatThrownBy(() -> lifecycle.deleteAddress(clusterId, "orders.addr", false))
                .isInstanceOf(OperationHeldException.class);

        ArgumentCaptor<Operation> operation = ArgumentCaptor.forClass(Operation.class);
        verify(gate).run(operation.capture(), any());
        assertThat(operation.getValue().params()).isEqualTo(new AddressDeleteParams(clusterId, "orders.addr"));
    }

    @Test
    void aQueueDeleteIsEstimatedFromTheScrapeAndPinnedToTheQueuesIdentity() {
        assertThat(lifecycle.reachOfQueueDelete(clusterId, QUEUE)).isEqualTo(new Reach(5, "orders.addr|ANYCAST"));

        Effect effect = queueDeleteOperation.estimate(new QueueDeleteParams(clusterId, QUEUE, false, false));

        assertThat(effect).isEqualTo(new Effect(5, "messages", "orders.addr|ANYCAST", null));
        assertThat(queueDeleteOperation.traits(new QueueDeleteParams(clusterId, QUEUE, false, false)))
                .isEqualTo(Set.of(Trait.DESTRUCTIVE));
    }

    @Test
    void aQueueBoundAnotherWayAfterTheRequestHasAnotherStateKey() {
        String asked = queueDeleteOperation
                .estimate(new QueueDeleteParams(clusterId, QUEUE, false, false))
                .stateKey();
        jdbc.update("DELETE FROM queue_snapshot WHERE cluster_id = ?", clusterId);
        upsert.upsertBatch(List.of(
                new QueueRow(clusterId, nodeId, "other.addr", QUEUE, "MULTICAST", true, 5, 0, 0, 0, 0, 0, 0, false)));

        String now = queueDeleteOperation
                .estimate(new QueueDeleteParams(clusterId, QUEUE, false, false))
                .stateKey();

        assertThat(now).isNotEqualTo(asked);
    }

    @Test
    void aQueueThatIsGoneCannotBeEstimated() {
        QueueDeleteParams params = new QueueDeleteParams(clusterId, "gone", false, false);

        assertThatThrownBy(() -> queueDeleteOperation.estimate(params)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void theOperatorReadsTheClusterByNameAndWhatIsDeleted() {
        assertThat(queueDeleteOperation.summary(new QueueDeleteParams(clusterId, QUEUE, false, false)))
                .startsWith("Delete queue orders on c-");
        assertThat(addressDeleteOperation.summary(new AddressDeleteParams(clusterId, "orders.addr")))
                .startsWith("Delete address orders.addr on c-");
    }

    @Test
    void aReplayThatFailedOnANodeFailsTheRequest() {
        when(gate.run(any(), any())).thenAnswer(call -> ((java.util.function.Supplier<?>) call.getArgument(1)).get());
        when(ops.messageCount(any(), anyString())).thenReturn(5L);
        doThrow(new io.github.sudoitir.artemisstudio.platform.broker.ManagementRefusal(
                        io.github.sudoitir.artemisstudio.platform.broker.ManagementRefusal.Kind.BOUND_QUEUES, "bound"))
                .when(ops)
                .deleteAddress(any(), anyString(), anyString());

        AddressDeleteParams params = new AddressDeleteParams(clusterId, "orders.addr");

        assertThatThrownBy(() -> addressDeleteOperation.replay(params))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed on");
    }
}
