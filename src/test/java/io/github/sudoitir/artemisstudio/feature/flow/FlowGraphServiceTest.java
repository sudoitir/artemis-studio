package io.github.sudoitir.artemisstudio.feature.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.feature.flow.ClientEdges.Edge;
import io.github.sudoitir.artemisstudio.feature.flow.ClientEdges.Kind;
import io.github.sudoitir.artemisstudio.feature.flow.FlowQuery.GroupBy;
import io.github.sudoitir.artemisstudio.feature.flow.FlowQuery.Rank;
import io.github.sudoitir.artemisstudio.feature.flow.FlowStore.NodeSample;
import io.github.sudoitir.artemisstudio.feature.flow.FlowStore.StoredEdge;
import io.github.sudoitir.artemisstudio.feature.flow.web.FlowViews.Delivery;
import io.github.sudoitir.artemisstudio.feature.flow.web.FlowViews.EdgeKind;
import io.github.sudoitir.artemisstudio.feature.flow.web.FlowViews.Fault;
import io.github.sudoitir.artemisstudio.feature.flow.web.FlowViews.FlowEdgeView;
import io.github.sudoitir.artemisstudio.feature.flow.web.FlowViews.FlowGraphView;
import io.github.sudoitir.artemisstudio.feature.flow.web.FlowViews.FlowNodeView;
import io.github.sudoitir.artemisstudio.feature.flow.web.FlowViews.NodeKind;
import io.github.sudoitir.artemisstudio.feature.flow.web.FlowViews.NodeSampleState;
import io.github.sudoitir.artemisstudio.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.artemisstudio.kernel.security.Permissions;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsService;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterDirectory;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterNode;
import io.github.sudoitir.artemisstudio.platform.scrape.MetricSamples;
import io.github.sudoitir.artemisstudio.platform.scrape.MetricSamples.SubjectRate;
import io.github.sudoitir.artemisstudio.platform.scrape.QueueSnapshot;
import io.github.sudoitir.artemisstudio.platform.scrape.QueueSnapshots;
import io.github.sudoitir.artemisstudio.platform.scrape.ScrapeSettings;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FlowGraphServiceTest {

    private final Instant now = Instant.parse("2026-09-14T10:00:00Z");
    private final UUID clusterId = UUID.randomUUID();
    private final UUID nodeA = UUID.randomUUID();

    private final FlowStore store = mock(FlowStore.class);
    private final QueueSnapshots snapshots = mock(QueueSnapshots.class);
    private final MetricSamples metrics = mock(MetricSamples.class);
    private final ClusterDirectory directory = mock(ClusterDirectory.class);
    private final SettingsService settings = mock(SettingsService.class);
    private final FlowDemand demand = mock(FlowDemand.class);
    private final ClusterAccessGuard access = mock(ClusterAccessGuard.class);

    private final List<QueueSnapshot> queues = new ArrayList<>();
    private final List<StoredEdge> edges = new ArrayList<>();
    private final List<NodeSample> samples = new ArrayList<>();
    private final Map<String, SubjectRate> added = new HashMap<>();
    private final Map<String, SubjectRate> acked = new HashMap<>();

    private FlowGraphService service;

    @BeforeEach
    void wire() {
        ClusterNode node = mock(ClusterNode.class);
        when(node.getId()).thenReturn(nodeA);
        when(node.getName()).thenReturn("node-a");
        when(directory.nodes(clusterId)).thenReturn(List.of(node));
        when(settings.duration(FlowSettings.SAMPLE_INTERVAL)).thenReturn(Duration.ofSeconds(15));
        when(settings.duration(ScrapeSettings.TIER_C)).thenReturn(Duration.ofMinutes(5));
        when(snapshots.forCluster(clusterId)).thenReturn(queues);
        when(store.edges(clusterId)).thenReturn(edges);
        when(store.nodeSamples(clusterId)).thenReturn(samples);
        when(metrics.latestRateWithTimeBySubject(eq(clusterId), eq("messagesAdded"), any(), any()))
                .thenReturn(added);
        when(metrics.latestRateWithTimeBySubject(eq(clusterId), eq("messagesAcked"), any(), any()))
                .thenReturn(acked);
        service = new FlowGraphService(
                store, snapshots, metrics, directory, settings, demand, access, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void aReadChecksPermissionAndCountsTheClusterAsObserved() {
        service.graph(clusterId, query(null, 40));

        verify(access).requireCluster(clusterId, Permissions.CLUSTER_READ);
        verify(demand).renew(clusterId);
    }

    @Test
    void aProduceRouteConsumePathIsDrawnWithDeliverySemanticsAndSampledRates() {
        queue("orders", "orders", "ANYCAST", 3, 1);
        rate(added, "orders", 42.0, Duration.ofSeconds(15));
        edge(Kind.PRODUCE, "order-svc", "orders", "", 40.0, 2, false);
        edge(Kind.CONSUME, "billing", "orders", "orders", 41.0, 3, false);
        samples.add(sample(null, 5, 5));

        FlowGraphView graph = service.graph(clusterId, query(null, 40));

        assertThat(graph.nodes())
                .extracting(FlowNodeView::id)
                .containsExactlyInAnyOrder("address:orders", "queue:orders", "producer:order-svc", "consumer:billing");
        FlowEdgeView route = edge(graph, EdgeKind.ROUTE);
        assertThat(route.delivery()).isEqualTo(Delivery.SHARED);
        assertThat(route.rate()).isEqualTo(42.0);
        assertThat(route.averagedOverSeconds()).isNull();
        assertThat(edge(graph, EdgeKind.CONSUME).members()).isEqualTo(3);
        assertThat(edge(graph, EdgeKind.CONSUME).source()).isEqualTo("queue:orders");
        assertThat(edge(graph, EdgeKind.PRODUCE).target()).isEqualTo("address:orders");
        assertThat(graph.measuring()).isFalse();
    }

    @Test
    void anUnmeasuredRateIsNullNotZeroAndASlowTierRateStatesItsAverage() {
        queue("orders", "orders", "MULTICAST", 0, 1);
        queue("audit", "orders", "MULTICAST", 0, 1);
        rate(added, "audit", 2.0, Duration.ofMinutes(5));

        FlowGraphView graph = service.graph(clusterId, query(null, 40));

        FlowEdgeView orders = graph.edges().stream()
                .filter(e -> e.target().equals("queue:orders"))
                .findFirst()
                .orElseThrow();
        FlowEdgeView audit = graph.edges().stream()
                .filter(e -> e.target().equals("queue:audit"))
                .findFirst()
                .orElseThrow();
        assertThat(orders.rate()).isNull();
        assertThat(orders.delivery()).isEqualTo(Delivery.COPY);
        assertThat(audit.averagedOverSeconds()).isEqualTo(300);
        assertThat(graph.kpis().inRate()).isEqualTo(2.0);
        assertThat(graph.measuring()).isTrue();
    }

    @Test
    void theBusiestPathsAreShownAndTheBoundIsStated() {
        for (int i = 0; i < 50; i++) {
            queue("q" + i, "a" + i, "ANYCAST", 0, 1);
            // Spread across decades: rates inside one half-decade bucket tie and fall back to name order.
            rate(added, "q" + i, Math.pow(10, i / 5.0), Duration.ofSeconds(15));
        }

        FlowGraphView graph = service.graph(clusterId, FlowQuery.of(null, 1, Rank.IN, 500, GroupBy.CLIENT_ID));
        FlowGraphView top = service.graph(clusterId, query(null, 3));

        assertThat(graph.totals().clamped()).isTrue();
        assertThat(graph.totals().limit()).isEqualTo(FlowQuery.MAX_LIMIT);
        assertThat(top.totals().paths()).isEqualTo(50);
        assertThat(top.totals().shown()).isEqualTo(3);
        assertThat(top.nodes()).extracting(FlowNodeView::label).contains("q49", "q48");
    }

    @Test
    void rankingIsStableForSmallRateChanges() {
        assertThat(FlowGraphService.bucket(101.0)).isEqualTo(FlowGraphService.bucket(120.0));
        assertThat(FlowGraphService.bucket(0.0)).isGreaterThan(FlowGraphService.bucket(null));
        assertThat(FlowGraphService.bucket(1000.0)).isGreaterThan(FlowGraphService.bucket(100.0));
    }

    @Test
    void aQueueWithBacklogAndNoConsumerIsAFaultCountedInTheTotals() {
        queue("dead", "dead", "ANYCAST", 1200, 0);

        FlowGraphView graph = service.graph(clusterId, query(null, 40));

        assertThat(graph.nodes())
                .filteredOn(n -> n.kind() == NodeKind.QUEUE)
                .singleElement()
                .satisfies(n -> assertThat(n.faults()).containsExactly(Fault.NO_CONSUMER));
        assertThat(graph.kpis().faults()).isEqualTo(1);
        assertThat(graph.kpis().backlog()).isEqualTo(1200);
    }

    @Test
    void aStalledConsumerIsMarkedOnItsEdgeAndNode() {
        queue("orders", "orders", "ANYCAST", 10, 1);
        edge(Kind.CONSUME, "billing", "orders", "orders", 0.0, 1, true);

        FlowGraphView graph = service.graph(clusterId, query(null, 40));

        assertThat(edge(graph, EdgeKind.CONSUME).faults()).containsExactly(Fault.STALLED);
    }

    @Test
    void focusingAQueueShowsItsNeighbourhoodAndAFocusOnNothingSaysSo() {
        queue("orders", "orders", "ANYCAST", 0, 1);
        queue("refunds", "refunds", "ANYCAST", 0, 1);
        edge(Kind.CONSUME, "billing", "orders", "orders", 1.0, 1, false);
        edge(Kind.CONSUME, "billing", "refunds", "refunds", 1.0, 1, false);

        FlowGraphView one = service.graph(clusterId, query("queue:orders", 40));
        FlowGraphView two = service.graph(clusterId, FlowQuery.of("queue:orders", 2, Rank.IN, 40, GroupBy.CLIENT_ID));
        FlowGraphView none = service.graph(clusterId, query("queue:gone", 40));

        assertThat(one.nodes()).extracting(FlowNodeView::id).doesNotContain("queue:refunds");
        assertThat(two.nodes()).extracting(FlowNodeView::id).contains("queue:refunds");
        assertThat(none.focus().matched()).isFalse();
        assertThat(none.nodes()).isEmpty();
    }

    @Test
    void groupingByHostMergesClientsFromOneHost() {
        queue("orders", "orders", "ANYCAST", 0, 2);
        edges.add(new StoredEdge(
                nodeA,
                now,
                new Edge(Kind.CONSUME, "a", "u1", "10.0.0.5", "CORE", "orders", "orders", 1.0, 0, 1, false)));
        edges.add(new StoredEdge(
                nodeA,
                now,
                new Edge(Kind.CONSUME, "b", "u2", "10.0.0.5", "AMQP", "orders", "orders", 2.0, 0, 1, false)));

        FlowGraphView byHost = service.graph(clusterId, FlowQuery.of(null, 1, Rank.IN, 40, GroupBy.HOST));
        FlowGraphView byClient = service.graph(clusterId, query(null, 40));

        assertThat(byHost.nodes())
                .filteredOn(n -> n.kind() == NodeKind.CONSUMER)
                .singleElement()
                .satisfies(n -> {
                    assertThat(n.label()).isEqualTo("10.0.0.5");
                    assertThat(n.members()).isEqualTo(2);
                });
        assertThat(edge(byHost, EdgeKind.CONSUME).rate()).isEqualTo(3.0);
        assertThat(byClient.nodes())
                .filteredOn(n -> n.kind() == NodeKind.CONSUMER)
                .hasSize(2);
    }

    @Test
    void aNodeThatRefusedTheListingIsNamedWithTheAccessThatGrantsIt() {
        samples.add(sample("PERMISSION_DENIED", 0, 0));

        FlowGraphView graph = service.graph(clusterId, query(null, 40));

        assertThat(graph.brokerNodes()).singleElement().satisfies(n -> {
            assertThat(n.name()).isEqualTo("node-a");
            assertThat(n.state()).isEqualTo(NodeSampleState.PERMISSION_DENIED);
            assertThat(n.brokerXmlSnippet()).contains("role-access");
        });
    }

    @Test
    void aTruncatedSampleIsStated() {
        samples.add(new NodeSample(nodeA, clusterId, now, 2, 2, 5000, 12430, null, null));

        FlowGraphView graph = service.graph(clusterId, query(null, 40));

        assertThat(graph.brokerNodes().getFirst().truncated()).isTrue();
    }

    @Test
    void aQueueConsumedBeforeTheSlowSweepSawItIsStillDrawn() {
        edge(Kind.CONSUME, "billing", "fresh", "fresh", null, 1, false);

        FlowGraphView graph = service.graph(clusterId, query(null, 40));

        assertThat(graph.nodes()).extracting(FlowNodeView::id).contains("queue:fresh", "consumer:billing");
        assertThat(graph.nodes())
                .filteredOn(n -> n.id().equals("queue:fresh"))
                .singleElement()
                .satisfies(n -> assertThat(n.messageCount()).isNull());
    }

    @Test
    void brokerInternalQueuesAreNotDrawn() {
        queue("$.artemis.internal.sf.cluster.abc", "$.artemis.internal.sf.cluster.abc", "MULTICAST", 0, 1);
        queue("activemq.notifications.studio", "activemq.notifications", "MULTICAST", 0, 1);

        FlowGraphView graph = service.graph(clusterId, query(null, 40));

        assertThat(graph.nodes()).isEmpty();
        assertThat(graph.totals().paths()).isZero();
    }

    private FlowQuery query(String focus, int limit) {
        return FlowQuery.of(focus, 1, Rank.IN, limit, GroupBy.CLIENT_ID);
    }

    private void queue(String name, String address, String routingType, long messages, long consumers) {
        queues.add(new QueueSnapshot(
                clusterId, nodeA, name, address, routingType, true, false, now, messages, consumers, 0, 0, 0, 0, 0));
    }

    private void rate(Map<String, SubjectRate> map, String queue, double rate, Duration span) {
        map.put(queue, new SubjectRate(rate, now.minusSeconds(5), span));
    }

    private void edge(
            Kind kind, String client, String address, String queue, Double rate, int members, boolean stalled) {
        edges.add(new StoredEdge(
                nodeA,
                now.minusSeconds(5),
                new Edge(kind, client, "artemis", "10.0.0.5", "CORE", address, queue, rate, 0, members, stalled)));
    }

    private NodeSample sample(String errorKind, int producers, int consumers) {
        return new NodeSample(nodeA, clusterId, now, producers, producers, consumers, consumers, null, errorKind);
    }

    private static FlowEdgeView edge(FlowGraphView graph, EdgeKind kind) {
        return graph.edges().stream().filter(e -> e.kind() == kind).findFirst().orElseThrow();
    }
}
