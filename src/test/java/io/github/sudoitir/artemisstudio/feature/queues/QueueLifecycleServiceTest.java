package io.github.sudoitir.artemisstudio.feature.queues;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.feature.queues.LifecycleRequests.CreateQueueRequest;
import io.github.sudoitir.artemisstudio.kernel.audit.internal.persistence.AuditEventEntity;
import io.github.sudoitir.artemisstudio.kernel.audit.internal.persistence.AuditEventRepository;
import io.github.sudoitir.artemisstudio.platform.broker.Attempt;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerConnectionException;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.platform.broker.BulkCapExceededException;
import io.github.sudoitir.artemisstudio.platform.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.platform.broker.ManagementRefusal;
import io.github.sudoitir.artemisstudio.platform.broker.QueueRow;
import io.github.sudoitir.artemisstudio.platform.clusters.LifecycleOutcome;
import io.github.sudoitir.artemisstudio.platform.clusters.LifecycleOutcome.NodeOutcome;
import io.github.sudoitir.artemisstudio.platform.clusters.LifecycleOutcome.NodeStatus;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.artemisstudio.platform.scrape.QueueSnapshotUpsert;
import io.github.sudoitir.artemisstudio.support.AdminAuthenticationExtension;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
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
    io.github.sudoitir.artemisstudio.platform.scrape.QueueSnapshots snapshots;

    @Autowired
    AuditEventRepository auditEvents;

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

    private JolokiaBrokerClient client;

    private UUID clusterId;
    private UUID liveId;
    private UUID deadId;

    @BeforeEach
    void setUp() {
        clusterId = clusters.save(new ClusterEntity("c-" + UUID.randomUUID(), null, null))
                .getId();
        liveId = node("live", LIVE_URL, true);
        deadId = node("dead", DEAD_URL, false);

        client = mock(JolokiaBrokerClient.class);
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
        onTheNode(0, QUEUE);
        when(ops.messageCount(any(), anyString())).thenReturn(42L);

        LifecycleOutcome outcome = ok(lifecycle.deleteQueue(clusterId, QUEUE, true, false, false));

        assertThat(outcome.totalAffected()).isEqualTo(42L);
        assertThat(outcome.nodes().stream()
                        .filter(n -> n.nodeId().equals(liveId))
                        .findFirst()
                        .orElseThrow()
                        .affected())
                .isEqualTo(42L);
        verify(ops, never()).destroyQueue(any(), anyString(), anyString(), anyBoolean());
    }

    // ---- the bulk cap (D6) ------------------------------------------------

    @Test
    void aDeleteOverTheCapIsRefusedWithoutAnOverride() {
        seedQueue();
        onTheNode(0, QUEUE);
        when(ops.messageCount(any(), anyString())).thenReturn(Long.MAX_VALUE / 2);

        assertThatThrownBy(() -> lifecycle.deleteQueue(clusterId, QUEUE, false, false, false))
                .isInstanceOf(BulkCapExceededException.class);
        verify(ops, never()).destroyQueue(any(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void aDeleteOverTheCapProceedsWithAnExplicitOverride() {
        seedQueue();
        onTheNode(0, QUEUE);
        when(ops.messageCount(any(), anyString())).thenReturn(Long.MAX_VALUE / 2);

        LifecycleOutcome outcome = ok(lifecycle.deleteQueue(clusterId, QUEUE, false, true, false));

        assertThat(status(outcome, liveId)).isEqualTo(NodeStatus.APPLIED);
        verify(ops).destroyQueue(any(), anyString(), eq(QUEUE), eq(false));
    }

    @Test
    void aDeletedQueueLeavesTheListingAtOnce() {
        // The snapshot is a cache of what the brokers run. Left until the next sweep, the
        // deleted queue stays on screen — the delete reads as though it did nothing, and
        // every action on the row that is left fails against an MBean that is gone.
        seedQueue();
        onTheNode(0, QUEUE);

        LifecycleOutcome outcome = ok(lifecycle.deleteQueue(clusterId, QUEUE, false, false, false));

        assertThat(status(outcome, liveId)).isEqualTo(NodeStatus.APPLIED);
        assertThat(snapshots.forCluster(clusterId))
                .extracting(s -> s.queueName())
                .doesNotContain(QUEUE);
    }

    @Test
    void aPreviewLeavesTheListingAlone() {
        seedQueue();
        onTheNode(0, QUEUE);

        ok(lifecycle.deleteQueue(clusterId, QUEUE, true, false, false));

        assertThat(snapshots.forCluster(clusterId))
                .extracting(s -> s.queueName())
                .contains(QUEUE);
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

    // ---- delete: consumers and dependent diverts (ADR-0084) ----------------

    private static final String ADDRESS = "orders.addr";

    /** What the delete preflight reads on the live node: the queue's consumers and the address's queues. */
    private void onTheNode(long consumers, String... addressQueues) {
        when(ops.deleteState(any(), anyString(), eq(ADDRESS), eq(QUEUE), anyString()))
                .thenReturn(new QueueLifecycleOperations.DeleteState(
                        List.of(addressQueues).contains(QUEUE), consumers, List.of(addressQueues)));
    }

    private void diverts(DivertRow... rows) {
        when(divertOps.listDiverts(any(), any(), any())).thenReturn(List.of(rows));
    }

    private static DivertRow divert(String name, String from, String to) {
        return new DivertRow(null, null, name, name, from, to, null, "STRIP", null, Map.of(), false, false);
    }

    private NodeOutcome live(LifecycleOutcome outcome) {
        return outcome.nodes().stream()
                .filter(n -> n.nodeId().equals(liveId))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void aQueueWithConsumersIsRefusedInThePreviewNamingTheFlagAndIsNotDestroyed() {
        seedQueue();
        onTheNode(2, QUEUE);

        NodeOutcome preview = live(ok(lifecycle.deleteQueue(clusterId, QUEUE, true, false, false)));
        assertThat(preview.status()).isEqualTo(NodeStatus.FAILED);
        assertThat(preview.error()).contains("2 consumers").contains("disconnectConsumers");

        NodeOutcome real = live(ok(lifecycle.deleteQueue(clusterId, QUEUE, false, false, false)));
        assertThat(real.status()).isEqualTo(NodeStatus.FAILED);
        verify(ops, never()).destroyQueue(any(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void disconnectingConsumersIsWarnedInThePreviewAndSentForReal() {
        seedQueue();
        onTheNode(2, QUEUE);

        NodeOutcome preview = live(ok(lifecycle.deleteQueue(clusterId, QUEUE, true, false, true)));
        assertThat(preview.status()).isEqualTo(NodeStatus.WOULD_APPLY);
        assertThat(preview.error()).contains("2 consumers").contains("disconnected");

        NodeOutcome real = live(ok(lifecycle.deleteQueue(clusterId, QUEUE, false, false, true)));
        assertThat(real.status()).isEqualTo(NodeStatus.APPLIED);
        verify(ops).destroyQueue(any(), anyString(), eq(QUEUE), eq(true));
        assertThat(realAudit("DELETE_QUEUE").getParams()).contains("disconnectConsumers");
    }

    @Test
    void aDivertIntoTheQueuesLastAddressIsNamedInThePreviewAndRemovedBeforeTheQueue() {
        seedQueue();
        onTheNode(0, QUEUE);
        diverts(divert("feed", "incoming", ADDRESS));

        NodeOutcome preview = live(ok(lifecycle.deleteQueue(clusterId, QUEUE, true, false, false)));
        assertThat(preview.status()).isEqualTo(NodeStatus.WOULD_APPLY);
        assertThat(preview.error())
                .contains("feed")
                .contains("incoming → " + ADDRESS)
                .contains("broker.xml");
        verify(divertOps, never()).destroyDivert(any(), anyString(), anyString());

        NodeOutcome real = live(ok(lifecycle.deleteQueue(clusterId, QUEUE, false, false, false)));
        assertThat(real.status()).isEqualTo(NodeStatus.APPLIED);
        assertThat(real.error()).contains("feed");
        InOrder order = inOrder(divertOps, ops);
        order.verify(divertOps).destroyDivert(any(), anyString(), eq("feed"));
        order.verify(ops).destroyQueue(any(), anyString(), eq(QUEUE), eq(false));
        // Enough in the audit row to recreate the divert exactly.
        assertThat(realAudit("DELETE_QUEUE").getOutcomeDetail())
                .contains("feed")
                .contains("incoming")
                .contains("STRIP");
    }

    @Test
    void aDivertIntoAnAddressThatKeepsAnotherQueueIsLeftAlone() {
        seedQueue();
        onTheNode(0, QUEUE, "orders.audit");
        diverts(divert("feed", "incoming", ADDRESS));

        NodeOutcome real = live(ok(lifecycle.deleteQueue(clusterId, QUEUE, false, false, false)));

        assertThat(real.status()).isEqualTo(NodeStatus.APPLIED);
        verify(divertOps, never()).destroyDivert(any(), anyString(), anyString());
    }

    @Test
    void aDivertFromTheQueuesAddressIsKeptAndTheReasonGiven() {
        seedQueue();
        onTheNode(0, QUEUE);
        diverts(divert("fan-out", ADDRESS, "elsewhere"));

        NodeOutcome preview = live(ok(lifecycle.deleteQueue(clusterId, QUEUE, true, false, false)));
        assertThat(preview.error()).contains("fan-out").contains("kept");

        ok(lifecycle.deleteQueue(clusterId, QUEUE, false, false, false));
        verify(divertOps, never()).destroyDivert(any(), anyString(), anyString());
    }

    @Test
    void anIncomingDivertIsKeptWhileADivertFromTheAddressKeepsItBound() {
        // incoming → ADDRESS → elsewhere: the address outlives its last queue through fan-out,
        // so removing feed would cut a chain the delete does not otherwise touch.
        seedQueue();
        onTheNode(0, QUEUE);
        diverts(divert("feed", "incoming", ADDRESS), divert("fan-out", ADDRESS, "elsewhere"));

        NodeOutcome preview = live(ok(lifecycle.deleteQueue(clusterId, QUEUE, true, false, false)));
        assertThat(preview.error()).contains("'feed'").contains("fan-out").contains("kept");
        assertThat(preview.error()).doesNotContain("Removed with the queue");

        ok(lifecycle.deleteQueue(clusterId, QUEUE, false, false, false));
        verify(divertOps, never()).destroyDivert(any(), anyString(), anyString());
    }

    @Test
    void aCaptureTapIsNeverRemovedByAQueueDelete() {
        seedQueue();
        onTheNode(0, QUEUE);
        String tap = DivertOperations.CAPTURE_PREFIX + "x";
        diverts(divert(tap, ADDRESS, tap));

        NodeOutcome preview = live(ok(lifecycle.deleteQueue(clusterId, QUEUE, true, false, false)));
        assertThat(preview.error()).contains(tap).contains("removed by its capture subscription");

        ok(lifecycle.deleteQueue(clusterId, QUEUE, false, false, false));
        verify(divertOps, never()).destroyDivert(any(), anyString(), anyString());
    }

    @Test
    void aCaptureTapStillCoveringAnotherQueueOnTheAddressIsNamedAsKept() {
        seedQueue();
        onTheNode(0, QUEUE, "orders.audit");
        String tap = DivertOperations.CAPTURE_PREFIX + "x";
        diverts(divert(tap, ADDRESS, tap));
        when(captureTaps.coversWithout(clusterId, tap, ADDRESS, QUEUE)).thenReturn(true);

        NodeOutcome preview = live(ok(lifecycle.deleteQueue(clusterId, QUEUE, true, false, false)));

        assertThat(preview.error()).contains(tap).contains("kept").doesNotContain("removed by its capture");
    }

    @Test
    void aDeclaredDependentDivertIsNamedAsComingBack() {
        seedQueue();
        onTheNode(0, QUEUE);
        diverts(divert("feed", "incoming", ADDRESS));
        when(declaredDiverts.names(clusterId)).thenReturn(java.util.Set.of("feed"));

        NodeOutcome preview = live(ok(lifecycle.deleteQueue(clusterId, QUEUE, true, false, false)));

        assertThat(preview.error()).contains("declared configuration");
    }

    @Test
    void aDivertThatCannotBeRemovedKeepsTheQueue() {
        seedQueue();
        onTheNode(0, QUEUE);
        diverts(divert("feed", "incoming", ADDRESS));
        doThrow(new BrokerConnectionException(BrokerConnectionException.Kind.UNREACHABLE, "connection refused"))
                .when(divertOps)
                .destroyDivert(any(), anyString(), eq("feed"));

        NodeOutcome real = live(ok(lifecycle.deleteQueue(clusterId, QUEUE, false, false, false)));

        assertThat(real.status()).isEqualTo(NodeStatus.FAILED);
        assertThat(real.error()).contains("feed").contains("not deleted");
        verify(ops, never()).destroyQueue(any(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void aQueueDeleteThatFailsAfterItsDivertsNamesTheDivertsAlreadyRemoved() {
        seedQueue();
        onTheNode(0, QUEUE);
        diverts(divert("feed", "incoming", ADDRESS));
        doThrow(new ManagementRefusal(ManagementRefusal.Kind.HAS_CONSUMERS, "A consumer attached."))
                .when(ops)
                .destroyQueue(any(), anyString(), eq(QUEUE), anyBoolean());

        NodeOutcome real = live(ok(lifecycle.deleteQueue(clusterId, QUEUE, false, false, false)));

        assertThat(real.status()).isEqualTo(NodeStatus.FAILED);
        assertThat(real.error()).contains("A consumer attached.").contains("feed");
    }

    @Test
    void aNodeWithoutTheQueueRemovesNothingAndIsAlreadyThere() {
        seedQueue();
        onTheNode(0, "someone.else");
        diverts(divert("feed", "incoming", ADDRESS));
        doThrow(new ManagementRefusal(ManagementRefusal.Kind.ALREADY, "Already absent"))
                .when(ops)
                .destroyQueue(any(), anyString(), eq(QUEUE), anyBoolean());

        NodeOutcome real = live(ok(lifecycle.deleteQueue(clusterId, QUEUE, false, false, false)));

        assertThat(real.status()).isEqualTo(NodeStatus.ALREADY);
        verify(divertOps, never()).destroyDivert(any(), anyString(), anyString());
    }

    // ---- queue resolution -------------------------------------------------

    @Test
    void aQueueTheScrapeHasNotReachedIsFoundOnTheLiveNode() {
        String mbean = "org.apache.activemq.artemis:broker=\"b\",component=addresses,address=\"" + ADDRESS
                + "\",subcomponent=queues,routing-type=\"anycast\",queue=\"" + QUEUE + "\"";
        tools.jackson.databind.node.ObjectNode value = new tools.jackson.databind.json.JsonMapper().createObjectNode();
        value.putObject(mbean).put("MessageCount", 0);
        when(client.single(org.mockito.ArgumentMatchers.argThat(
                        r -> r != null && "read".equals(r.type()) && r.mbean().contains("queue=\"" + QUEUE + "\""))))
                .thenReturn(new io.github.sudoitir.artemisstudio.platform.broker.JolokiaResponse(
                        200, value, null, null, null));
        onTheNode(0, QUEUE);

        NodeOutcome preview = live(ok(lifecycle.deleteQueue(clusterId, QUEUE, true, false, false)));

        assertThat(preview.status()).isEqualTo(NodeStatus.WOULD_APPLY);
    }

    @Test
    void aQueueNoNodeHasIsNotFound() {
        // What a broker answers a pattern read that matches nothing.
        when(client.single(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new io.github.sudoitir.artemisstudio.platform.broker.JolokiaResponse(
                        404, null, "No MBean with pattern found", "javax.management.InstanceNotFoundException", null));
        assertThatThrownBy(() -> lifecycle.deleteQueue(clusterId, QUEUE, true, false, false))
                .isInstanceOf(io.github.sudoitir.artemisstudio.kernel.core.NotFoundException.class);
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

    /** The one real (not previewed) row for an action. */
    private AuditEventEntity realAudit(String action) {
        List<AuditEventEntity> real =
                auditFor(action).stream().filter(e -> !e.isDryRun()).toList();
        assertThat(real).hasSize(1);
        return real.get(0);
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
