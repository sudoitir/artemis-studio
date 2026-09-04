package io.github.sudoitir.artemisstudio.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.github.sudoitir.artemisstudio.broker.BrokerConnectionException;
import io.github.sudoitir.artemisstudio.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.config.ArtemisStudioProperties;
import io.github.sudoitir.artemisstudio.config.ArtemisStudioProperties.RateLimit;
import io.github.sudoitir.artemisstudio.domain.topology.SplitBrainRegistry;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeRepository;
import io.github.sudoitir.artemisstudio.persist.ClusterEntity;
import io.github.sudoitir.artemisstudio.persist.ClusterRepository;
import io.github.sudoitir.artemisstudio.persist.MetricSampleWriter;
import io.github.sudoitir.artemisstudio.persist.QueueSnapshotUpsert;
import io.github.sudoitir.artemisstudio.sse.SseHub;
import io.github.sudoitir.artemisstudio.sse.StreamSignals;
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

    private final JsonMapper mapper = new JsonMapper();

    @Mock
    ClusterRepository clusters;

    @Mock
    BrokerNodeRepository nodes;

    @Mock
    BrokerConnections connections;

    @Mock
    ScrapePersistence persist;

    @Mock
    QueueSnapshotUpsert upsert;

    @Mock
    MetricSampleWriter metrics;

    @Mock
    io.github.sudoitir.artemisstudio.broker.core.CoreSubscriptionManager coreSubscriptions;

    @Mock
    io.github.sudoitir.artemisstudio.service.SettingsService settings;

    NodeCallLimiter limiter;
    ScrapeCycle scrapeCycle;
    SweepCursor sweepCursor;
    ScrapeScheduler scheduler;

    @BeforeEach
    void setUp() {
        limiter = new NodeCallLimiter(
                new ArtemisStudioProperties(null, null, null, new RateLimit(50), null, null, null, null, null));
        scrapeCycle = new ScrapeCycle(new SplitBrainRegistry());
        sweepCursor = new SweepCursor();
        scheduler = new ScrapeScheduler(
                settings,
                clusters,
                nodes,
                connections,
                limiter,
                scrapeCycle,
                persist,
                sweepCursor,
                upsert,
                metrics,
                new StreamSignals(new SseHub()),
                coreSubscriptions);
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
    void tierARefreshesEachManageableNodeOncePerTickWithTheCycleNumber() {
        UUID clusterId = UUID.randomUUID();
        ClusterEntity cluster = cluster("c");
        BrokerNodeEntity a = node(clusterId, "a", GOOD);
        BrokerNodeEntity b = node(clusterId, "b", GOOD);

        when(clusters.findAll()).thenReturn(List.of(cluster));
        when(nodes.findByClusterIdOrderByNameAsc(cluster.getId())).thenReturn(List.of(a, b));
        when(connections.forCluster(eq(cluster.getId()), eq(GOOD)))
                .thenReturn(client("search-broker.json", "ha-read-primary.json"))
                .thenReturn(client("search-broker.json", "ha-read-primary.json"));

        scheduler.tierA();

        verify(persist, times(2)).applyTierA(any(), any(), eq(1L));
        verify(persist, never()).recordNodeError(any(), anyString());
    }

    @Test
    void aFailingNodeIsRecordedAndDoesNotAbortItsSiblings() {
        UUID clusterId = UUID.randomUUID();
        ClusterEntity cluster = cluster("c");
        BrokerNodeEntity good = node(clusterId, "good", GOOD);
        BrokerNodeEntity bad = node(clusterId, "bad", BAD);

        when(clusters.findAll()).thenReturn(List.of(cluster));
        when(nodes.findByClusterIdOrderByNameAsc(cluster.getId())).thenReturn(List.of(bad, good));
        when(connections.forCluster(eq(cluster.getId()), eq(BAD)))
                .thenThrow(BrokerConnectionException.of(BrokerConnectionException.Kind.UNREACHABLE));
        when(connections.forCluster(eq(cluster.getId()), eq(GOOD)))
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

        when(clusters.findAll()).thenReturn(List.of(clusterA, clusterB));
        when(nodes.findByClusterIdOrderByNameAsc(clusterA.getId())).thenReturn(List.of(nodeA));
        when(nodes.findByClusterIdOrderByNameAsc(clusterB.getId())).thenReturn(List.of(nodeB));
        when(connections.forCluster(eq(clusterA.getId()), eq(BAD)))
                .thenThrow(BrokerConnectionException.of(BrokerConnectionException.Kind.UNREACHABLE));
        when(connections.forCluster(eq(clusterB.getId()), eq(GOOD)))
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

        when(clusters.findAll()).thenReturn(List.of(cluster));
        when(nodes.findByClusterIdOrderByNameAsc(cluster.getId())).thenReturn(List.of(n));
        // search + listQueues page 1 (fixture reports count=1, so page 1 is the last page)
        when(connections.forCluster(eq(cluster.getId()), eq(GOOD)))
                .thenReturn(client("search-broker.json", "list-queues.json"));

        scheduler.tierC();

        verify(upsert, times(1)).upsertBatch(any());
        verify(metrics, times(1)).appendQueueSamples(any());
        verify(upsert, times(1)).reapStale(any(), any());
    }

    @Test
    void aShortenedIntervalSchedulesTheNextRunSoonerWithoutARestart() {
        java.util.concurrent.atomic.AtomicReference<java.time.Duration> interval =
                new java.util.concurrent.atomic.AtomicReference<>(java.time.Duration.ofSeconds(60));
        org.springframework.scheduling.Trigger trigger = ScrapeScheduler.fixedDelay(interval::get);

        java.time.Instant last = java.time.Instant.parse("2026-09-04T10:00:00Z");
        org.springframework.scheduling.TriggerContext ctx = new org.springframework.scheduling.TriggerContext() {
            @Override
            public java.time.Instant lastScheduledExecution() {
                return last;
            }

            @Override
            public java.time.Instant lastActualExecution() {
                return last;
            }

            @Override
            public java.time.Instant lastCompletion() {
                return last;
            }
        };

        java.time.Instant before = trigger.nextExecution(ctx);
        interval.set(java.time.Duration.ofSeconds(5)); // operator lowers the cadence in Settings
        java.time.Instant after = trigger.nextExecution(ctx);

        org.assertj.core.api.Assertions.assertThat(before).isEqualTo(last.plusSeconds(60));
        org.assertj.core.api.Assertions.assertThat(after).isEqualTo(last.plusSeconds(5));
        org.assertj.core.api.Assertions.assertThat(after).isBefore(before);
    }
}
