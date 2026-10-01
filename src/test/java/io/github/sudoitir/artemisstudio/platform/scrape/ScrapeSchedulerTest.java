package io.github.sudoitir.artemisstudio.platform.scrape;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.github.sudoitir.artemisstudio.kernel.jobs.JobStatuses;
import io.github.sudoitir.artemisstudio.kernel.stream.SseHub;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerConnectionException;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.platform.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterDirectory;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterDutyAcquired;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterDutyReleased;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterService;
import io.github.sudoitir.artemisstudio.platform.clusters.NodeStateRecorder;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.ClusterEntity;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScrapeSchedulerTest {

    private static final String GOOD = "http://good:8161/console/jolokia";
    private static final String BAD = "http://bad:8161/console/jolokia";
    private static final String STALLED = "http://stalled:8161/console/jolokia";

    private final JsonMapper mapper = new JsonMapper();

    @Mock
    ClusterDirectory clusters;

    @Mock
    ClusterService clusterService;

    @Mock
    BrokerConnections connections;

    @Mock
    NodeStateRecorder persist;

    @Mock
    QueueSnapshotUpsert upsert;

    @Mock
    MetricSampleWriter metrics;

    @Mock
    io.github.sudoitir.artemisstudio.platform.broker.CoreSubscriptionManager coreSubscriptions;

    @Mock
    io.github.sudoitir.artemisstudio.kernel.settings.SettingsService settings;

    @Mock
    org.springframework.context.ApplicationEventPublisher eventPublisher;

    @Mock
    io.github.sudoitir.artemisstudio.platform.clusters.ClusterOwnership ownership;

    ScrapeCycle scrapeCycle;
    SweepCursor sweepCursor;
    ScrapeScheduler scheduler;

    @BeforeEach
    void setUp() {
        when(ownership.owns(any())).thenReturn(true);
        scrapeCycle = new ScrapeCycle(persist);
        sweepCursor = new SweepCursor();
        scheduler = new ScrapeScheduler(
                settings,
                clusters,
                ownership,
                clusterService,
                connections,
                scrapeCycle,
                persist,
                sweepCursor,
                upsert,
                metrics,
                new StreamSignals(org.mockito.Mockito.mock(SseHub.class)),
                coreSubscriptions,
                eventPublisher,
                new JobStatuses(
                        new net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor(
                                config -> java.util.Optional.of(() -> {})),
                        io.micrometer.observation.ObservationRegistry.NOOP,
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
    }

    private JolokiaBrokerClient client(String... fixtures) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        for (String fixture : fixtures) {
            server.expect(requestTo(GOOD)).andRespond(body(fixture));
        }
        return new JolokiaBrokerClient(builder.build(), GOOD, mapper);
    }

    private static ResponseCreator body(String fixture) {
        try {
            String json = new String(
                    new ClassPathResource("jolokia/" + fixture).getContentAsByteArray(), StandardCharsets.UTF_8);
            return withSuccess(json, MediaType.APPLICATION_JSON);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static BrokerNodeEntity node(UUID clusterId, String name, String url) {
        BrokerNodeEntity n = BrokerNodeEntity.fromSeed(clusterId, name, "PRIMARY", null);
        n.attachManagementUrl(url);
        setId(n, UUID.randomUUID());
        return n;
    }

    private static ClusterEntity cluster(String name) {
        ClusterEntity c = new ClusterEntity(name, null, null);
        setId(c, UUID.randomUUID());
        return c;
    }

    /** The JPA {@code @GeneratedValue} id is only set on persist; these plain-Mockito tests need one up front. */
    private static void setId(Object entity, UUID id) {
        try {
            var field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void discoveryRunsForEveryClusterAndOneFailureDoesNotStopAnother() {
        ClusterEntity broken = cluster("broken");
        ClusterEntity fine = cluster("fine");
        when(clusters.clusters()).thenReturn(List.of(broken, fine));
        doThrow(new IllegalStateException("boom")).when(clusterService).rediscover(broken.getId());

        scheduler.discovery();

        verify(clusterService).rediscover(broken.getId());
        verify(clusterService).rediscover(fine.getId());
    }

    @Test
    void tierARefreshesEachManageableNodeOncePerTickWithTheCycleNumber() {
        UUID clusterId = UUID.randomUUID();
        ClusterEntity cluster = cluster("c");
        BrokerNodeEntity a = node(clusterId, "a", GOOD);
        BrokerNodeEntity b = node(clusterId, "b", GOOD);

        when(clusters.owned()).thenReturn(List.of(cluster));
        when(clusters.nodes(cluster.getId())).thenReturn(List.of(a, b));
        when(connections.forCluster(cluster.getId(), GOOD))
                .thenReturn(client("search-broker.json", "ha-read-primary.json"))
                .thenReturn(client("search-broker.json", "ha-read-primary.json"));

        scheduler.tierA();

        verify(persist, times(2)).applyTierA(any(), any(), eq(1L));
        verify(persist, never()).recordNodeError(any(), anyString());
    }

    @Test
    void tiersAAndBLeaveAClusterThisReplicaDoesNotOwnAlone() {
        when(clusters.owned()).thenReturn(List.of());
        when(clusters.clusters()).thenReturn(List.of(cluster("c")));

        scheduler.tierA();
        scheduler.tierB();

        verify(connections, never()).forCluster(any(), any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void takingAClusterOverScrapesItAtOnce() {
        UUID clusterId = UUID.randomUUID();
        ClusterEntity cluster = cluster("c");
        BrokerNodeEntity a = node(clusterId, "a", GOOD);
        when(clusters.nodes(any())).thenReturn(List.of(a));
        when(connections.forCluster(any(), any())).thenReturn(client("search-broker.json", "ha-read-primary.json"));

        scheduler.onDutyAcquired(new ClusterDutyAcquired(cluster.getId()));

        verify(persist, timeout(5000)).applyTierA(any(), any(), eq(1L));
        verify(eventPublisher, timeout(5000)).publishEvent(any(ScrapeTierCompleted.class));
    }

    @Test
    void aStalledNodeDoesNotDelayTheProbeOfItsSiblings() throws Exception {
        UUID clusterId = UUID.randomUUID();
        ClusterEntity cluster = cluster("c");
        BrokerNodeEntity stalled = node(clusterId, "stalled", STALLED);
        BrokerNodeEntity healthy = node(clusterId, "healthy", GOOD);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        when(clusters.owned()).thenReturn(List.of(cluster));
        when(clusters.nodes(cluster.getId())).thenReturn(List.of(stalled, healthy));
        when(connections.forCluster(cluster.getId(), STALLED)).thenAnswer(call -> {
            release.await();
            return client("search-broker.json", "ha-read-primary.json");
        });
        when(connections.forCluster(cluster.getId(), GOOD))
                .thenReturn(client("search-broker.json", "ha-read-primary.json", "ha-read-primary.json"));
        try {
            scheduler.tierA();
            scheduler.tierA();

            verify(persist).applyTierA(eq(healthy.getId()), any(), eq(1L));
            verify(persist).applyTierA(eq(healthy.getId()), any(), eq(2L));
            verify(connections, times(1)).forCluster(cluster.getId(), STALLED);
        } finally {
            release.countDown();
        }
        verify(persist, timeout(5000)).applyTierA(eq(stalled.getId()), any(), eq(1L));
    }

    @Test
    void aTierAReadThatFailedInsideAnOkResponseLeavesTheNodeAsItWas() {
        UUID clusterId = UUID.randomUUID();
        ClusterEntity cluster = cluster("c");
        BrokerNodeEntity n = node(clusterId, "n", GOOD);
        when(clusters.owned()).thenReturn(List.of(cluster));
        when(clusters.nodes(cluster.getId())).thenReturn(List.of(n));
        when(connections.forCluster(cluster.getId(), GOOD))
                .thenReturn(client("search-broker.json", "ha-read-instance-not-found.json"));

        scheduler.tierA();

        verify(persist, never()).applyTierA(any(), any(), anyLong());
        verify(persist, never()).recordNodeError(any(), anyString());
    }

    @Test
    void aFailedQueueListingLeavesTheNodeReachable() {
        UUID clusterId = UUID.randomUUID();
        ClusterEntity cluster = cluster("c");
        BrokerNodeEntity n = node(clusterId, "n", GOOD);
        when(clusters.owned()).thenReturn(List.of(cluster));
        when(clusters.clusters()).thenReturn(List.of(cluster));
        when(clusters.nodes(cluster.getId())).thenReturn(List.of(n));
        when(connections.forCluster(cluster.getId(), GOOD))
                .thenReturn(client("search-broker.json", "list-queues-failed.json"))
                .thenReturn(client("search-broker.json", "list-queues-failed.json"));

        scheduler.tierB();
        scheduler.tierC();

        verify(upsert, never()).upsertBatch(any());
        verify(persist, never()).recordNodeError(any(), anyString());
    }

    @Test
    void aFailureToPersistWhatWasScrapedLeavesTheNodeReachable() {
        UUID clusterId = UUID.randomUUID();
        ClusterEntity cluster = cluster("c");
        BrokerNodeEntity n = node(clusterId, "n", GOOD);
        when(clusters.owned()).thenReturn(List.of(cluster));
        when(clusters.nodes(cluster.getId())).thenReturn(List.of(n));
        when(connections.forCluster(cluster.getId(), GOOD))
                .thenReturn(client("search-broker.json", "list-queues.json"));
        doThrow(new IllegalStateException("database down")).when(upsert).upsertBatch(any());

        scheduler.tierB();

        verify(upsert).upsertBatch(any());
        verify(persist, never()).recordNodeError(any(), anyString());
    }

    @Test
    void losingAClusterDropsItsSubscriptionsCycleAndSignals() {
        UUID clusterId = UUID.randomUUID();
        scrapeCycle.next(clusterId);

        scheduler.onDutyReleased(new ClusterDutyReleased(clusterId));

        assertThat(scrapeCycle.current(clusterId)).isZero();
        verify(coreSubscriptions, timeout(5000)).forget(clusterId);
    }

    @Test
    void aClusterLostWhileATierARanLeavesNoSubscriptionBehind() {
        ClusterEntity cluster = cluster("c");
        when(clusters.owned()).thenReturn(List.of(cluster));
        when(clusters.nodes(any())).thenReturn(List.of());
        when(ownership.owns(any())).thenReturn(false);

        scheduler.tierA();

        verify(coreSubscriptions).forget(cluster.getId());
    }

    @Test
    void aFailingNodeIsRecordedAndDoesNotAbortItsSiblings() {
        UUID clusterId = UUID.randomUUID();
        ClusterEntity cluster = cluster("c");
        BrokerNodeEntity good = node(clusterId, "good", GOOD);
        BrokerNodeEntity bad = node(clusterId, "bad", BAD);

        when(clusters.owned()).thenReturn(List.of(cluster));
        when(clusters.nodes(cluster.getId())).thenReturn(List.of(bad, good));
        when(connections.forCluster(cluster.getId(), BAD))
                .thenThrow(BrokerConnectionException.of(BrokerConnectionException.Kind.UNREACHABLE));
        when(connections.forCluster(cluster.getId(), GOOD))
                .thenReturn(client("search-broker.json", "ha-read-primary.json"));

        scheduler.tierA();

        verify(persist, times(1)).applyTierA(any(), any(), eq(1L));
        verify(persist, times(1)).recordNodeError(any(), anyString());
    }

    @Test
    void oneClusterFailingDoesNotStopAnother() {
        UUID clusterAId = UUID.randomUUID();
        UUID clusterBId = UUID.randomUUID();
        ClusterEntity clusterA = cluster("a");
        ClusterEntity clusterB = cluster("b");
        BrokerNodeEntity nodeA = node(clusterAId, "na", BAD);
        BrokerNodeEntity nodeB = node(clusterBId, "nb", GOOD);

        when(clusters.owned()).thenReturn(List.of(clusterA, clusterB));
        when(clusters.nodes(clusterA.getId())).thenReturn(List.of(nodeA));
        when(clusters.nodes(clusterB.getId())).thenReturn(List.of(nodeB));
        when(connections.forCluster(clusterA.getId(), BAD))
                .thenThrow(BrokerConnectionException.of(BrokerConnectionException.Kind.UNREACHABLE));
        when(connections.forCluster(clusterB.getId(), GOOD))
                .thenReturn(client("search-broker.json", "ha-read-primary.json"));

        scheduler.tierA();

        verify(persist, times(1)).recordNodeError(any(), anyString());
        verify(persist, times(1)).applyTierA(any(), any(), anyLong());
    }

    @Test
    void tierCUpsertsThePageWritesSamplesAndReapsWhenTheSweepCompletes() {
        UUID clusterId = UUID.randomUUID();
        ClusterEntity cluster = cluster("c");
        BrokerNodeEntity n = node(clusterId, "n", GOOD);

        when(clusters.clusters()).thenReturn(List.of(cluster));
        when(clusters.nodes(cluster.getId())).thenReturn(List.of(n));
        // search + listQueues page 1 (fixture reports count=1, so page 1 is the last page)
        when(connections.forCluster(cluster.getId(), GOOD))
                .thenReturn(client("search-broker.json", "list-queues.json"));

        scheduler.tierC();

        verify(upsert, times(1)).upsertBatch(any());
        verify(metrics, times(1)).appendQueueSamples(any());
        verify(upsert, times(1)).reapStale(any(), any());
    }

    // The trigger itself — that a changed interval is honoured without a restart —
    // is covered by DynamicTriggersTest, where the shared helper now lives.
}
