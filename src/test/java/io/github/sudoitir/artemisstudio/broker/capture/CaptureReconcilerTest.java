package io.github.sudoitir.artemisstudio.broker.capture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.persist.AuditEventEntity;
import io.github.sudoitir.artemisstudio.persist.AuditService;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeRepository;
import io.github.sudoitir.artemisstudio.persist.CaptureMode;
import io.github.sudoitir.artemisstudio.persist.ClusterLock;
import io.github.sudoitir.artemisstudio.persist.MessageCaptureNodeRepository;
import io.github.sudoitir.artemisstudio.persist.MessageIndexSubscriptionEntity;
import io.github.sudoitir.artemisstudio.persist.MessageIndexSubscriptionRepository;
import io.github.sudoitir.artemisstudio.scheduler.NodeCallLimiter;
import io.github.sudoitir.artemisstudio.sql.QueryAst;
import io.github.sudoitir.artemisstudio.sql.QueryPlan;
import io.github.sudoitir.artemisstudio.sql.QueryPlanner;
import io.github.sudoitir.artemisstudio.sql.SqlQueryParser;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The converge loop's four obligations (ADR-0062 D4, D5): install what is missing,
 * remove what this instance owns and no longer wants, never touch what belongs to
 * another Studio, and do nothing at all when nothing has drifted.
 *
 * <p>The last one is not a performance nicety. A pass that re-asserts every tap would
 * be a management write per tap per interval, forever, against a broker Studio is
 * supposed to be gentle with (non-negotiable #1).
 */
class CaptureReconcilerTest {

    private static final String INSTANCE = "abc12345";
    private static final UUID CLUSTER = UUID.randomUUID();
    private static final UUID NODE = UUID.randomUUID();
    private static final UUID SUBSCRIPTION = UUID.randomUUID();
    private static final String WANTED = "artemis-studio.capture." + INSTANCE + ".ORDER.IN." + SUBSCRIPTION;
    private static final String OTHER_STUDIO = "artemis-studio.capture.ff99ff99.ORDER.IN." + UUID.randomUUID();

    private MessageIndexSubscriptionRepository subscriptions;
    private MessageCaptureNodeRepository captureNodes;
    private CaptureTap tap;
    private CaptureConsumer consumers;
    private CaptureReconciler reconciler;

    @BeforeEach
    void setUp() {
        subscriptions = mock(MessageIndexSubscriptionRepository.class);
        captureNodes = mock(MessageCaptureNodeRepository.class);
        BrokerNodeRepository nodes = mock(BrokerNodeRepository.class);
        BrokerConnections connections = mock(BrokerConnections.class);
        NodeCallLimiter limiter = mock(NodeCallLimiter.class);
        ClusterLock lock = mock(ClusterLock.class);
        tap = mock(CaptureTap.class);
        consumers = mock(CaptureConsumer.class);
        CaptureBus bus = mock(CaptureBus.class);
        StudioInstance instance = mock(StudioInstance.class);
        QueryPlanner planner = mock(QueryPlanner.class);
        AuditService audit = mock(AuditService.class);
        AuditEventEntity auditEvent = mock(AuditEventEntity.class);

        when(instance.id()).thenReturn(INSTANCE);
        BrokerNodeEntity node = node();
        when(nodes.findByClusterIdOrderByNameAsc(CLUSTER)).thenReturn(List.of(node));
        when(connections.forCluster(any(), any())).thenReturn(mock(JolokiaBrokerClient.class));
        MessageIndexSubscriptionEntity subscription = subscription();
        when(subscriptions.findByEnabledTrue()).thenReturn(List.of(subscription));
        QueryPlan plan = plan();
        when(planner.plan(any(), any())).thenReturn(plan);
        when(audit.begin(any(), anyString(), anyString(), any(), any(), any(), any(), eq(false)))
                .thenReturn(auditEvent);
        when(captureNodes.findBySubscriptionIdAndNodeId(any(), any())).thenReturn(Optional.empty());
        when(subscriptions.findById(any())).thenReturn(Optional.empty());
        doAnswer(invocation -> {
                    ((Runnable) invocation.getArgument(1)).run();
                    return true;
                })
                .when(lock)
                .runIfHeld(any(), any());

        reconciler = new CaptureReconciler(
                subscriptions,
                captureNodes,
                nodes,
                connections,
                limiter,
                lock,
                tap,
                consumers,
                bus,
                instance,
                new SqlQueryParser(),
                planner,
                audit,
                mock(CaptureLoss.class));
    }

    @Test
    void installsTheTapThatIsMissing() throws Exception {
        when(tap.installedNames(any(), eq(INSTANCE))).thenReturn(List.of());

        reconciler.reconcile();

        verify(tap).install(any(), eq(INSTANCE), any());
        verify(consumers).start(any());
    }

    @Test
    void doesNothingWhenNothingHasDrifted() throws Exception {
        when(tap.installedNames(any(), eq(INSTANCE))).thenReturn(List.of(WANTED));
        when(consumers.isDraining(NODE, WANTED)).thenReturn(true);
        when(consumers.drainingOn(NODE)).thenReturn(java.util.Set.of(WANTED));

        reconciler.reconcile();
        reconciler.reconcile();

        verify(tap, never()).install(any(), any(), any());
        verify(tap, never()).remove(any(), any(), any());
        verify(consumers, never()).start(any());
    }

    @Test
    void removesItsOwnOrphanWhenTheSubscriptionIsGone() {
        when(subscriptions.findByEnabledTrue()).thenReturn(List.of());
        when(tap.installedNames(any(), eq(INSTANCE))).thenReturn(List.of(WANTED));

        // No capturing subscription at all: the pass is not even entered per cluster,
        // so the orphan is removed by the pass that still has one for that cluster.
        reconciler.reconcileCluster(CLUSTER);

        verify(tap).remove(any(), eq(INSTANCE), eq(WANTED));
        verify(consumers).stop(NODE, WANTED);
    }

    @Test
    void neverTouchesAnotherStudiosTap() {
        // installedNames only ever reports this instance's own names — the filter is in
        // the tap — so a divert belonging to another Studio is invisible here. This
        // pins the consequence: nothing in a pass removes a name it did not recognise.
        when(tap.installedNames(any(), eq(INSTANCE))).thenReturn(List.of());

        reconciler.reconcileCluster(CLUSTER);

        verify(tap, never()).remove(any(), any(), eq(OTHER_STUDIO));
        verify(tap, times(1)).installedNames(any(), eq(INSTANCE));
    }

    // ---- fixtures --------------------------------------------------------

    /** The entity is deliberately closed to construction; what matters here is what it answers. */
    private static BrokerNodeEntity node() {
        BrokerNodeEntity entity = mock(BrokerNodeEntity.class);
        when(entity.getId()).thenReturn(NODE);
        when(entity.getName()).thenReturn("primary");
        when(entity.getJolokiaUrl()).thenReturn("http://primary:8161/console/jolokia");
        when(entity.getCoreUrl()).thenReturn("tcp://primary:61616");
        when(entity.getActive()).thenReturn(true);
        return entity;
    }

    private static MessageIndexSubscriptionEntity subscription() {
        MessageIndexSubscriptionEntity entity = new MessageIndexSubscriptionEntity();
        entity.setId(SUBSCRIPTION);
        entity.setClusterId(CLUSTER);
        entity.setQueuePattern("ORDER.IN");
        entity.setMode(CaptureMode.CAPTURE);
        entity.setRingSize(10_000);
        entity.setMaxRate(500);
        entity.setBodyCapBytes(262_144);
        entity.setEnabled(true);
        return entity;
    }

    private static QueryPlan plan() {
        QueryPlan.Target target =
                new QueryPlan.Target(NODE, "primary", "ORDER.IN", "ORDER.IN", "ANYCAST", 0, Instant.now(), true);
        return new QueryPlan(
                null,
                QueryAst.Source.BROKER,
                List.of(target),
                null,
                false,
                List.of(),
                List.of(),
                0,
                100,
                false,
                List.of());
    }
}
