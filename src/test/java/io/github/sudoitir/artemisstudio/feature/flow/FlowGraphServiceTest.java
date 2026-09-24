package io.github.sudoitir.artemisstudio.feature.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import io.github.sudoitir.artemisstudio.feature.flow.web.FlowViews.NodeRole;
import io.github.sudoitir.artemisstudio.feature.flow.web.FlowViews.NodeSampleState;
import io.github.sudoitir.artemisstudio.feature.flow.web.FlowViews.RateSource;
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
    private final List<FlowStore.StoredRoute> routes = new ArrayList<>();
    private final UUID nodeB = UUID.randomUUID();
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
        when(store.routes(clusterId)).thenReturn(routes);
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

        FlowGraphView graph = service.graph(clusterId, FlowQuery.of(null, 1, Rank.IN, 500, GroupBy.CLIENT_ID, null));
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
        FlowGraphView two =
                service.graph(clusterId, FlowQuery.of("queue:orders", 2, Rank.IN, 40, GroupBy.CLIENT_ID, null));
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

        FlowGraphView byHost = service.graph(clusterId, FlowQuery.of(null, 1, Rank.IN, 40, GroupBy.HOST, null));
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

    @Test
    void anExclusiveDivertReroutesMarksTheAddressesQueuesBypassedAndStatesPartialPresence() {
        queue("ORDERS.in", "ORDERS", "ANYCAST", 0, 1);
        samples.add(sample(null, 1, 1));
        samples.add(new NodeSample(nodeB, clusterId, now, 1, 1, 1, 1, null, null));
        route(nodeA, FlowStore.RouteKind.DIVERT, "orders-audit", "ORDERS", "AUDIT", null, true, true, null);

        FlowGraphView graph = service.graph(clusterId, query(null, 40));

        FlowEdgeView divert = edge(graph, EdgeKind.DIVERT);
        assertThat(divert.source()).isEqualTo("address:ORDERS");
        assertThat(divert.target()).isEqualTo("address:AUDIT");
        assertThat(divert.exclusive()).isTrue();
        assertThat(divert.rateSource()).isEqualTo(RateSource.NONE);
        assertThat(divert.rate()).isNull();
        assertThat(divert.presentOn()).isEqualTo(1);
        assertThat(divert.presentOf()).isEqualTo(2);
        assertThat(divert.faults()).containsExactly(Fault.PARTIAL_PRESENCE);
        assertThat(edge(graph, EdgeKind.ROUTE).bypassed()).isTrue();
        assertThat(graph.kpis().faults()).isEqualTo(1);
    }

    @Test
    void aBridgeToAnAddressOutsideTheClusterIsRemoteAndItsOutageIsAFault() {
        queue("ORDERS.in", "ORDERS", "ANYCAST", 0, 1);
        samples.add(sample(null, 1, 1));
        route(nodeA, FlowStore.RouteKind.BRIDGE, "to-dc2", "ORDERS.in", "dc2.orders", null, false, false, 5.0);

        FlowGraphView graph = service.graph(clusterId, query(null, 40));

        FlowEdgeView bridge = edge(graph, EdgeKind.BRIDGE);
        assertThat(bridge.target()).isEqualTo("remote:bridge:dc2.orders");
        assertThat(bridge.rate()).isEqualTo(5.0);
        assertThat(bridge.faults()).containsExactly(Fault.BRIDGE_DOWN);
        assertThat(graph.nodes())
                .filteredOn(n -> n.kind() == NodeKind.REMOTE)
                .singleElement()
                .satisfies(n -> assertThat(n.role()).isEqualTo(NodeRole.BRIDGE_TARGET));
    }

    @Test
    void aStoreAndForwardQueueHopsToTheNodeItIsNamedForOnlyWithTheClusterLayer() {
        ClusterNode a = mock(ClusterNode.class);
        when(a.getId()).thenReturn(nodeA);
        when(a.getName()).thenReturn("node-a");
        ClusterNode b = mock(ClusterNode.class);
        when(b.getId()).thenReturn(nodeB);
        when(b.getName()).thenReturn("node-b");
        when(b.getArtemisNodeId()).thenReturn("abc-123");
        when(directory.nodes(clusterId)).thenReturn(List.of(a, b));
        route(
                nodeA,
                FlowStore.RouteKind.STORE_AND_FORWARD,
                "$.artemis.internal.sf.demo.abc-123",
                "$.artemis.internal.sf.demo.abc-123",
                "abc-123",
                null,
                false,
                true,
                7.0);

        FlowGraphView graph = service.graph(clusterId, query(null, 40));
        FlowGraphView without =
                service.graph(clusterId, FlowQuery.of(null, 1, Rank.IN, 40, GroupBy.CLIENT_ID, "DIVERTS"));

        FlowEdgeView hop = edge(graph, EdgeKind.CLUSTER_HOP);
        assertThat(hop.rate()).isEqualTo(7.0);
        assertThat(graph.nodes())
                .filteredOn(n -> n.id().equals(hop.target()))
                .singleElement()
                .satisfies(n -> {
                    assertThat(n.label()).isEqualTo("node-b");
                    assertThat(n.role()).isEqualTo(NodeRole.CLUSTER_NODE);
                });
        assertThat(graph.nodes())
                .filteredOn(n -> n.id().equals(hop.source()))
                .singleElement()
                .satisfies(n -> assertThat(n.role()).isEqualTo(NodeRole.STORE_AND_FORWARD));
        assertThat(without.edges()).noneMatch(e -> e.kind() == EdgeKind.CLUSTER_HOP);
    }

    @Test
    void aClusterHopNamesThePairsServingEndpointNotItsStandby() {
        ClusterNode serving = mock(ClusterNode.class);
        when(serving.getId()).thenReturn(nodeA);
        when(serving.getName()).thenReturn("artemis-secondary");
        when(serving.getArtemisNodeId()).thenReturn("pair-2");
        when(serving.getActive()).thenReturn(true);
        ClusterNode standby = mock(ClusterNode.class);
        when(standby.getId()).thenReturn(nodeB);
        when(standby.getName()).thenReturn("artemis-secondary-backup");
        when(standby.getArtemisNodeId()).thenReturn("pair-2");
        when(standby.getActive()).thenReturn(false);
        // The standby sorts after its serving endpoint, which is exactly how the directory lists them.
        when(directory.nodes(clusterId)).thenReturn(List.of(serving, standby));
        route(
                nodeA,
                FlowStore.RouteKind.STORE_AND_FORWARD,
                "$.artemis.internal.sf.demo.pair-2",
                "$.artemis.internal.sf.demo.pair-2",
                "pair-2",
                null,
                false,
                true,
                3.0);

        FlowGraphView graph = service.graph(clusterId, query(null, 40));

        assertThat(graph.nodes())
                .filteredOn(n -> n.kind() == NodeKind.REMOTE)
                .singleElement()
                .satisfies(n -> assertThat(n.label()).isEqualTo("artemis-secondary"));
    }

    @Test
    void temporaryQueuesCollapseIntoOneNodePerClientOnlyWhenAsked() {
        route(nodeA, FlowStore.RouteKind.TEMPORARY_QUEUE, "tmp.1", "tmp.1", "tmp.1", null, false, true, null);
        route(nodeA, FlowStore.RouteKind.TEMPORARY_QUEUE, "tmp.2", "tmp.2", "tmp.2", null, false, true, null);
        edge(Kind.CONSUME, "rpc-client", "tmp.1", "tmp.1", 1.0, 1, false);
        edge(Kind.CONSUME, "rpc-client", "tmp.2", "tmp.2", 2.0, 1, false);

        FlowGraphView hidden = service.graph(clusterId, query(null, 40));
        FlowGraphView shown =
                service.graph(clusterId, FlowQuery.of(null, 1, Rank.IN, 40, GroupBy.CLIENT_ID, "TEMPORARY"));

        assertThat(hidden.nodes()).isEmpty();
        assertThat(shown.nodes())
                .filteredOn(n -> n.kind() == NodeKind.QUEUE)
                .singleElement()
                .satisfies(n -> {
                    assertThat(n.label()).isEqualTo("temporary queues ×2");
                    assertThat(n.role()).isEqualTo(NodeRole.TEMPORARY);
                });
        assertThat(edge(shown, EdgeKind.CONSUME).rate()).isEqualTo(3.0);
    }

    @Test
    void aWildcardSubscriptionIsJoinedToTheAddressesItMatchesAndSaysWhatItAssumes() {
        queue("orders.all", "orders.#", "MULTICAST", 0, 1);
        edge(Kind.PRODUCE, "shop", "orders.eu", "", 3.0, 1, false);

        FlowGraphView graph = service.graph(clusterId, query(null, 40));

        FlowEdgeView wildcard = edge(graph, EdgeKind.WILDCARD);
        assertThat(wildcard.source()).isEqualTo("address:orders.eu");
        assertThat(wildcard.target()).isEqualTo("address:orders.#");
        assertThat(graph.assumptions()).containsExactly(FlowGraphService.WILDCARD_ASSUMPTION);
    }

    @Test
    void anAnonymousProducerIsDrawnToItsOwnNodeRatherThanOmitted() {
        edge(Kind.PRODUCE, "legacy-app", "", "", 4.0, 1, false);

        FlowGraphView graph = service.graph(clusterId, query(null, 40));

        assertThat(graph.nodes())
                .filteredOn(n -> n.kind() == NodeKind.ADDRESS)
                .singleElement()
                .satisfies(n -> {
                    assertThat(n.role()).isEqualTo(NodeRole.ANONYMOUS);
                    assertThat(n.label()).contains("chosen per message");
                });
        assertThat(edge(graph, EdgeKind.PRODUCE).rate()).isEqualTo(4.0);
    }

    @Test
    void studioCaptureTapsAreHiddenUnlessTheirLayerIsOn() {
        queue("ORDERS.in", "ORDERS", "ANYCAST", 0, 1);
        queue("artemis-studio.capture.x.q", "artemis-studio.capture.x.q", "ANYCAST", 0, 1);
        route(
                nodeA,
                FlowStore.RouteKind.DIVERT,
                "artemis-studio.capture.x",
                "ORDERS",
                "artemis-studio.capture.x.q",
                null,
                false,
                true,
                null);

        FlowGraphView hidden = service.graph(clusterId, query(null, 40));
        FlowGraphView shown =
                service.graph(clusterId, FlowQuery.of(null, 1, Rank.IN, 40, GroupBy.CLIENT_ID, "DIVERTS,CAPTURE"));

        assertThat(hidden.nodes()).noneMatch(n -> n.label().startsWith("artemis-studio.capture"));
        assertThat(hidden.edges()).noneMatch(e -> e.kind() == EdgeKind.DIVERT);
        assertThat(edge(shown, EdgeKind.DIVERT).studio()).isTrue();
    }

    @Test
    void aFilteredQueueCarriesItsFilterOnItsRoute() {
        queue("ORDERS.red", "ORDERS", "MULTICAST", 0, 1);
        route(
                nodeA,
                FlowStore.RouteKind.QUEUE_FILTER,
                "ORDERS.red",
                "ORDERS",
                "ORDERS.red",
                "color='red'",
                false,
                true,
                null);

        FlowGraphView graph = service.graph(clusterId, query(null, 40));

        assertThat(edge(graph, EdgeKind.ROUTE).filter()).isEqualTo("color='red'");
    }

    @Test
    void deadLetterRoutesAreDrawnOnlyWithTheirLayer() {
        queue("ORDERS.in", "ORDERS", "ANYCAST", 0, 1);
        route(nodeA, FlowStore.RouteKind.DEAD_LETTER, "#", "#", "DLQ", null, false, true, null);

        FlowGraphView hidden = service.graph(clusterId, query(null, 40));
        FlowGraphView shown =
                service.graph(clusterId, FlowQuery.of(null, 1, Rank.IN, 40, GroupBy.CLIENT_ID, "DEAD_LETTER"));

        assertThat(hidden.edges()).noneMatch(e -> e.kind() == EdgeKind.DEAD_LETTER);
        FlowEdgeView dla = edge(shown, EdgeKind.DEAD_LETTER);
        assertThat(dla.target()).isEqualTo("address:DLQ");
        assertThat(shown.nodes())
                .filteredOn(n -> n.id().equals("address:DLQ"))
                .singleElement()
                .satisfies(n -> assertThat(n.role()).isEqualTo(NodeRole.DEAD_LETTER));
    }

    @Test
    void anUnknownLayerIsRefusedAndBlankMeansTheDefaults() {
        assertThatThrownBy(() -> FlowQuery.of(null, 1, Rank.IN, 40, GroupBy.CLIENT_ID, "diverts,bogus"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bogus");
        assertThat(FlowQuery.of(null, 1, Rank.IN, 40, GroupBy.CLIENT_ID, " ").layers())
                .isEqualTo(FlowQuery.DEFAULT_LAYERS);
    }

    @Test
    void aNodeWhoseRoutingCouldNotBeReadSaysSoWhileItsClientsStayShown() {
        samples.add(new NodeSample(nodeA, clusterId, now, 1, 1, 1, 1, "divert read refused", "ROUTING_UNAVAILABLE"));

        FlowGraphView graph = service.graph(clusterId, query(null, 40));

        assertThat(graph.brokerNodes()).singleElement().satisfies(n -> {
            assertThat(n.state()).isEqualTo(NodeSampleState.ROUTING_UNAVAILABLE);
            assertThat(n.message()).contains("divert read refused");
        });
    }

    private void route(
            UUID node,
            FlowStore.RouteKind kind,
            String name,
            String source,
            String target,
            String filter,
            boolean exclusive,
            boolean connected,
            Double rate) {
        routes.add(new FlowStore.StoredRoute(
                node,
                now.minusSeconds(5),
                new FlowStore.Route(kind, name, source, target, filter, null, exclusive, connected, 0, rate)));
    }

    private FlowQuery query(String focus, int limit) {
        return FlowQuery.of(focus, 1, Rank.IN, limit, GroupBy.CLIENT_ID, null);
    }

    @Test
    void aBreakdownIsOnlyGivenWhenAskedFor() {
        queue("orders", "orders", "ANYCAST", 3, 1);
        edge(Kind.CONSUME, "billing", "orders", "orders", 4.0, 1, false);
        samples.add(sample(null, 0, 1));

        FlowGraphView graph = service.graph(clusterId, query(null, 40));

        assertThat(graph.nodes()).allSatisfy(n -> assertThat(n.byNode()).isNull());
        assertThat(graph.edges()).allSatisfy(e -> assertThat(e.byNode()).isNull());
        assertThat(graph.brokerNodes()).allSatisfy(b -> assertThat(b.backlog()).isNull());
    }

    @Test
    void aQueueOnTwoNodesStatesEachNodesBacklogConsumersAndRatesWhichAddUpToItsTotals() {
        ClusterNode a = mock(ClusterNode.class);
        when(a.getId()).thenReturn(nodeA);
        when(a.getName()).thenReturn("node-a");
        ClusterNode b = mock(ClusterNode.class);
        when(b.getId()).thenReturn(nodeB);
        when(b.getName()).thenReturn("node-b");
        List<ClusterNode> both = List.of(a, b);
        when(directory.nodes(clusterId)).thenReturn(both);
        // Stranded: node-b holds the backlog and has no consumer.
        queues.add(new QueueSnapshot(
                clusterId, nodeA, "orders", "orders", "ANYCAST", true, false, now, 10, 2, 0, 0, 0, 0, 0));
        queues.add(new QueueSnapshot(
                clusterId, nodeB, "orders", "orders", "ANYCAST", true, false, now, 9_000, 0, 0, 0, 0, 0, 0));
        Map<String, Map<UUID, SubjectRate>> addedByNode = Map.of(
                "orders",
                Map.of(
                        nodeA, new SubjectRate(3.0, now.minusSeconds(5), Duration.ofSeconds(15)),
                        nodeB, new SubjectRate(27.0, now.minusSeconds(5), Duration.ofSeconds(15))));
        when(metrics.latestRateWithTimeBySubjectAndNode(eq(clusterId), eq("messagesAdded"), any(), any()))
                .thenReturn(addedByNode);
        when(metrics.latestRateWithTimeBySubjectAndNode(eq(clusterId), eq("messagesAcked"), any(), any()))
                .thenReturn(Map.of(
                        "orders", Map.of(nodeA, new SubjectRate(3.0, now.minusSeconds(5), Duration.ofSeconds(15)))));
        samples.add(sample(null, 0, 2));
        samples.add(new NodeSample(nodeB, clusterId, now, 0, 0, 0, 0, null, null));

        FlowGraphView graph = service.graph(clusterId, query(null, 40).withByNode(true));

        FlowNodeView queue = graph.nodes().stream()
                .filter(n -> n.id().equals("queue:orders"))
                .findFirst()
                .orElseThrow();
        assertThat(queue.byNode())
                .extracting(sh -> sh.node() + " " + sh.messageCount() + "/" + sh.consumerCount() + " in " + sh.inRate()
                        + " out " + sh.outRate())
                .containsExactly("node-a 10/2 in 3.0 out 3.0", "node-b 9000/0 in 27.0 out null");
        // The totals are the sum of the parts, derived from the same per-node read.
        assertThat(edge(graph, EdgeKind.ROUTE).rate()).isEqualTo(30.0);
        assertThat(graph.nodes().stream()
                        .filter(n -> n.id().equals("address:orders"))
                        .findFirst()
                        .orElseThrow()
                        .byNode())
                .hasSize(2);
        assertThat(graph.brokerNodes())
                .extracting(bn -> bn.name() + " " + bn.backlog() + " " + bn.consumers())
                .containsExactly("node-a 10 2", "node-b 9000 0");
    }

    @Test
    void aClientRateOnANodeThatDidNotAnswerIsStaleInTheBreakdownNeverZero() {
        queue("orders", "orders", "ANYCAST", 3, 1);
        edge(Kind.CONSUME, "billing", "orders", "orders", 4.0, 1, false);
        samples.add(sample("UNREACHABLE", 0, 1));

        FlowGraphView graph = service.graph(clusterId, query(null, 40).withByNode(true));

        FlowEdgeView consume = edge(graph, EdgeKind.CONSUME);
        assertThat(consume.byNode()).singleElement().satisfies(r -> {
            assertThat(r.node()).isEqualTo("node-a");
            assertThat(r.rate()).isEqualTo(4.0);
            assertThat(r.stale()).isTrue();
        });
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
