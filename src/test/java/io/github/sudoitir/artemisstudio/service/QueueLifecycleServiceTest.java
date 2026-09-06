package io.github.sudoitir.artemisstudio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.broker.BrokerConnectionException;
import io.github.sudoitir.artemisstudio.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.broker.ManagementRefusal;
import io.github.sudoitir.artemisstudio.broker.QueueLifecycleOperations;
import io.github.sudoitir.artemisstudio.broker.QueueRow;
import io.github.sudoitir.artemisstudio.persist.AuditEventEntity;
import io.github.sudoitir.artemisstudio.persist.AuditEventRepository;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeRepository;
import io.github.sudoitir.artemisstudio.persist.ClusterEntity;
import io.github.sudoitir.artemisstudio.persist.ClusterRepository;
import io.github.sudoitir.artemisstudio.persist.QueueSnapshotUpsert;
import io.github.sudoitir.artemisstudio.service.LifecycleOutcome.NodeStatus;
import io.github.sudoitir.artemisstudio.support.AdminAuthenticationExtension;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import io.github.sudoitir.artemisstudio.web.dto.LifecycleRequests.CreateQueueRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The fan-out contract (ADR-0049): which nodes are targeted, what each reports,
 * and what the audit row says afterwards.
 *
 * <p>The broker layer is mocked. What is under test is the service's own
 * behaviour — target selection from live topology, per-node outcome collection,
 * the cap check, and the single audit row — not Jolokia, which
 * {@code QueueLifecycleOperations} owns and the live groundwork already verified.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class QueueLifecycleServiceTest extends PostgresIntegrationTest {

    private static final String LIVE_URL = "http://live:8161/console/jolokia";
    private static final String DEAD_URL = "http://dead:8161/console/jolokia";
    private static final String QUEUE = "orders";

    @Autowired
    QueueLifecycleService lifecycle;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerNodeRepository nodes;

    @Autowired
    QueueSnapshotUpsert upsert;

    @Autowired
    AuditEventRepository auditEvents;

    @MockitoBean
    BrokerConnections connections;

    @MockitoBean
    QueueLifecycleOperations ops;

    private UUID clusterId;
    private UUID liveId;
    private UUID deadId;

    @BeforeEach
    void setUp() {
        clusterId = clusters.save(new ClusterEntity("c-" + UUID.randomUUID(), null, null))
                .getId();
        liveId = node("live", LIVE_URL, true);
        deadId = node("dead", DEAD_URL, false);

        JolokiaBrokerClient client = mock(JolokiaBrokerClient.class);
        when(client.resolveBrokerObjectName()).thenReturn("org.apache.activemq.artemis:broker=\"b\"");
        when(connections.forCluster(eq(clusterId), anyString())).thenReturn(client);
    }

    /** Each node is its own logical node, so both are considered as targets. */
    private UUID node(String name, String url, boolean active) {
        BrokerNodeEntity n = BrokerNodeEntity.fromSeed(
                clusterId, name, "PRIMARY", UUID.randomUUID().toString());
        n.attachManagementUrl(url);
        n.applyHaState(active, "STARTED", "PRIMARY", null, 1L, "2.44.0", null, Instant.now());
        return nodes.save(n).getId();
    }

    private void seedQueue() {
        upsert.upsertBatch(List.of(
                new QueueRow(clusterId, liveId, "orders.addr", QUEUE, "ANYCAST", true, 5, 0, 0, 0, 0, 0, 0, false)));
    }

    @AfterEach
    void cleanUp() {
        if (clusterId != null) {
            clusters.deleteById(clusterId);
        }
    }

    private CreateQueueRequest createRequest() {
        return new CreateQueueRequest("orders.addr", QUEUE, "ANYCAST", true, null, null, null, null, null, null, null);
    }

    // ---- fan-out targeting ------------------------------------------------

    @Test
    void appliesToLiveNodesAndSkipsTheRestWithoutFailingTheCommand() {
        Attempt<LifecycleOutcome> attempt = lifecycle.createQueue(clusterId, createRequest(), false);

        LifecycleOutcome outcome = ((Attempt.Ok<LifecycleOutcome>) attempt).value();
        assertThat(outcome.nodes()).hasSize(2);
        assertThat(status(outcome, liveId)).isEqualTo(NodeStatus.APPLIED);
        // Never reached the command, so it is skipped — not a failure.
        assertThat(status(outcome, deadId)).isEqualTo(NodeStatus.SKIPPED_NOT_LIVE);
        assertThat(outcome.anyFailed()).isFalse();

        // The node that was not live must not have been called at all.
        verify(connections, never()).forCluster(clusterId, DEAD_URL);
    }

    @Test
    void aQueueThatAlreadyExistsWithTheSameConfigurationIsNotAnError() {
        when(ops.createQueue(any(), anyString(), any()))
                .thenThrow(new ManagementRefusal(ManagementRefusal.Kind.ALREADY, "AMQ229019: already exists"));
        when(ops.readQueueConfig(any(), anyString()))
                .thenReturn(Map.of("address", "orders.addr", "routing-type", "ANYCAST", "durable", true));

        LifecycleOutcome outcome = ok(lifecycle.createQueue(clusterId, createRequest(), false));

        assertThat(status(outcome, liveId)).isEqualTo(NodeStatus.ALREADY);
        assertThat(outcome.anyFailed()).isFalse();
    }

    @Test
    void aQueueThatExistsWithADifferentConfigurationFailsAndNamesTheDifference() {
        when(ops.createQueue(any(), anyString(), any()))
                .thenThrow(new ManagementRefusal(ManagementRefusal.Kind.ALREADY, "AMQ229019: already exists"));
        // Same name, different address — a genuinely different queue.
        when(ops.readQueueConfig(any(), anyString()))
                .thenReturn(Map.of("address", "somewhere.else", "routing-type", "ANYCAST", "durable", true));

        LifecycleOutcome outcome = ok(lifecycle.createQueue(clusterId, createRequest(), false));

        assertThat(status(outcome, liveId)).isEqualTo(NodeStatus.FAILED);
        assertThat(error(outcome, liveId)).contains("somewhere.else").contains("orders.addr");
        assertThat(outcome.anyFailed()).isTrue();
    }

    // ---- dry run ----------------------------------------------------------

    @Test
    void aDryRunMakesNoMutatingCall() {
        LifecycleOutcome outcome = ok(lifecycle.createQueue(clusterId, createRequest(), true));

        assertThat(outcome.dryRun()).isTrue();
        assertThat(status(outcome, liveId)).isEqualTo(NodeStatus.WOULD_APPLY);
        assertThat(status(outcome, deadId)).isEqualTo(NodeStatus.SKIPPED_NOT_LIVE);
        verify(ops, never()).createQueue(any(), anyString(), any());
    }

    @Test
    void aDeleteDryRunReportsWhatWouldBeDestroyedPerNodeAndDestroysNothing() {
        seedQueue();
        when(ops.messageCount(any(), anyString())).thenReturn(42L);

        LifecycleOutcome outcome = ok(lifecycle.deleteQueue(clusterId, QUEUE, true, false));

        assertThat(outcome.totalAffected()).isEqualTo(42L);
        assertThat(outcome.nodes().stream()
                        .filter(n -> n.nodeId().equals(liveId))
                        .findFirst()
                        .orElseThrow()
                        .affected())
                .isEqualTo(42L);
        verify(ops, never()).destroyQueue(any(), anyString(), anyString());
    }

    // ---- the bulk cap (D6) ------------------------------------------------

    @Test
    void aDeleteOverTheCapIsRefusedWithoutAnOverride() {
        seedQueue();
        when(ops.messageCount(any(), anyString())).thenReturn(Long.MAX_VALUE / 2);

        assertThatThrownBy(() -> lifecycle.deleteQueue(clusterId, QUEUE, false, false))
                .isInstanceOf(BulkCapExceededException.class);
        verify(ops, never()).destroyQueue(any(), anyString(), anyString());
    }

    @Test
    void aDeleteOverTheCapProceedsWithAnExplicitOverride() {
        seedQueue();
        when(ops.messageCount(any(), anyString())).thenReturn(Long.MAX_VALUE / 2);

        LifecycleOutcome outcome = ok(lifecycle.deleteQueue(clusterId, QUEUE, false, true));

        assertThat(status(outcome, liveId)).isEqualTo(NodeStatus.APPLIED);
        verify(ops).destroyQueue(any(), anyString(), eq(QUEUE));
    }

    // ---- partial failure (D3, D4) -----------------------------------------

    @Test
    void aPartialFailureIsReportedPerNodeAndRecordedAsFailed() {
        // Two live nodes, so one can succeed while the other refuses.
        UUID secondLive = node("live-2", "http://live2:8161/console/jolokia", true);
        doThrow(new BrokerConnectionException(BrokerConnectionException.Kind.UNREACHABLE, "connection refused"))
                .when(ops)
                .createQueue(any(), anyString(), any());

        LifecycleOutcome outcome = ok(lifecycle.createQueue(clusterId, createRequest(), false));

        assertThat(outcome.anyFailed()).isTrue();
        assertThat(status(outcome, secondLive)).isEqualTo(NodeStatus.FAILED);

        // One row for the whole command, recorded as failed, carrying every node.
        List<AuditEventEntity> events = auditFor("CREATE_QUEUE");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getOutcome()).isEqualTo("FAILURE");
        assertThat(events.get(0).getOutcomeDetail()).contains("FAILED").contains("SKIPPED_NOT_LIVE");
    }

    @Test
    void aSuccessfulFanOutIsOneAuditRowCarryingEveryNode() {
        ok(lifecycle.createQueue(clusterId, createRequest(), false));

        List<AuditEventEntity> events = auditFor("CREATE_QUEUE");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getOutcome()).isEqualTo("SUCCESS");
        assertThat(events.get(0).getOutcomeDetail()).contains("APPLIED").contains("SKIPPED_NOT_LIVE");
    }

    // ---- helpers ----------------------------------------------------------

    /**
     * Scoped to this test's own cluster. {@code audit_event.cluster_id} is
     * {@code ON DELETE SET NULL}, so rows from earlier tests in this class survive
     * their cluster and would otherwise be counted here.
     */
    private List<AuditEventEntity> auditFor(String action) {
        return auditEvents.findAll().stream()
                .filter(e -> action.equals(e.getAction()) && clusterId.equals(e.getClusterId()))
                .toList();
    }

    private static LifecycleOutcome ok(Attempt<LifecycleOutcome> attempt) {
        return ((Attempt.Ok<LifecycleOutcome>) attempt).value();
    }

    private static NodeStatus status(LifecycleOutcome outcome, UUID nodeId) {
        return outcome.nodes().stream()
                .filter(n -> n.nodeId().equals(nodeId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no outcome for node " + nodeId))
                .status();
    }

    private static String error(LifecycleOutcome outcome, UUID nodeId) {
        return outcome.nodes().stream()
                .filter(n -> n.nodeId().equals(nodeId))
                .findFirst()
                .orElseThrow()
                .error();
    }
}
