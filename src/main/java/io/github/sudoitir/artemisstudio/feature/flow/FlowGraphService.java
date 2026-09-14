package io.github.sudoitir.artemisstudio.feature.flow;

import io.github.sudoitir.artemisstudio.feature.flow.ClientEdges.Edge;
import io.github.sudoitir.artemisstudio.feature.flow.ClientEdges.Kind;
import io.github.sudoitir.artemisstudio.feature.flow.FlowQuery.Focus;
import io.github.sudoitir.artemisstudio.feature.flow.FlowQuery.GroupBy;
import io.github.sudoitir.artemisstudio.feature.flow.FlowQuery.Layer;
import io.github.sudoitir.artemisstudio.feature.flow.FlowStore.NodeSample;
import io.github.sudoitir.artemisstudio.feature.flow.FlowStore.Route;
import io.github.sudoitir.artemisstudio.feature.flow.FlowStore.RouteKind;
import io.github.sudoitir.artemisstudio.feature.flow.FlowStore.StoredEdge;
import io.github.sudoitir.artemisstudio.feature.flow.FlowStore.StoredRoute;
import io.github.sudoitir.artemisstudio.feature.flow.web.FlowViews.Delivery;
import io.github.sudoitir.artemisstudio.feature.flow.web.FlowViews.EdgeKind;
import io.github.sudoitir.artemisstudio.feature.flow.web.FlowViews.Fault;
import io.github.sudoitir.artemisstudio.feature.flow.web.FlowViews.FlowBrokerNodeView;
import io.github.sudoitir.artemisstudio.feature.flow.web.FlowViews.FlowEdgeView;
import io.github.sudoitir.artemisstudio.feature.flow.web.FlowViews.FlowFocusView;
import io.github.sudoitir.artemisstudio.feature.flow.web.FlowViews.FlowGraphView;
import io.github.sudoitir.artemisstudio.feature.flow.web.FlowViews.FlowKpis;
import io.github.sudoitir.artemisstudio.feature.flow.web.FlowViews.FlowNodeView;
import io.github.sudoitir.artemisstudio.feature.flow.web.FlowViews.FlowTotals;
import io.github.sudoitir.artemisstudio.feature.flow.web.FlowViews.NodeKind;
import io.github.sudoitir.artemisstudio.feature.flow.web.FlowViews.NodeRole;
import io.github.sudoitir.artemisstudio.feature.flow.web.FlowViews.NodeSampleState;
import io.github.sudoitir.artemisstudio.feature.flow.web.FlowViews.RateSource;
import io.github.sudoitir.artemisstudio.feature.queues.DivertOperations;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Assembles the bounded flow graph for one read (flow-visualization spec, design D6). Stateless:
 * every instance answers the same from the shared caches.
 *
 * <p>A <em>path</em> is one address-to-queue route with the clients producing to that address and
 * consuming from that queue, an address produced to that has no queue, a store-and-forward queue to
 * another node, or a client's collapsed temporary queues. Paths are what the bound counts and what
 * the ranking orders; routing layers are drawn around the chosen paths.
 */
@Service
@RequiredArgsConstructor
public class FlowGraphService {

    /** The management access that lets Studio's broker user list producers and consumers. */
    static final String LIST_ACCESS_SNIPPET = """
            <!-- etc/management.xml: allow the role Studio connects with to call list operations -->
            <authorisation>
               <role-access>
                  <match domain="org.apache.activemq.artemis">
                     <access method="list*" roles="amq"/>
                     <access method="get*" roles="amq"/>
                  </match>
               </role-access>
            </authorisation>""";

    static final String WILDCARD_ASSUMPTION = "Wildcard matches assume the broker's default syntax: words separated"
            + " by '.', '*' matching one word and '#' any number. A broker.xml that redefines these is not"
            + " readable over management.";

    private static final String UNIDENTIFIED = "unidentified client";

    /** The address key of producers that name none: their messages go wherever each message says. */
    static final String ANONYMOUS = "";

    static final String TEMPORARY_PREFIX = "temporary:";

    private final FlowStore store;
    private final QueueSnapshots snapshots;
    private final MetricSamples metrics;
    private final ClusterDirectory directory;
    private final SettingsService settings;
    private final FlowDemand demand;
    private final ClusterAccessGuard access;
    private final Clock clock;

    public FlowGraphView graph(UUID clusterId, FlowQuery query) {
        access.requireCluster(clusterId, Permissions.CLUSTER_READ);
        demand.renew(clusterId);

        Instant now = clock.instant();
        Duration sampleInterval = FlowSettings.sampleInterval(settings);
        Duration tierC = settings.duration(ScrapeSettings.TIER_C);
        Set<Layer> layers = query.layers();
        Map<UUID, String> nodeNames = new HashMap<>();
        Map<String, String> nodeNamesByArtemisId = new HashMap<>();
        for (ClusterNode n : directory.nodes(clusterId)) {
            nodeNames.put(n.getId(), n.getName());
            if (n.getArtemisNodeId() != null) {
                nodeNamesByArtemisId.put(n.getArtemisNodeId(), n.getName());
            }
        }

        List<NodeSample> samples = store.nodeSamples(clusterId);
        List<StoredRoute> routes = store.routes(clusterId);
        Set<String> temporaryQueues = new TreeSet<>();
        Map<String, String> queueFilters = new HashMap<>();
        for (StoredRoute r : routes) {
            if (r.route().kind() == RouteKind.TEMPORARY_QUEUE) {
                temporaryQueues.add(r.route().target());
            } else if (r.route().kind() == RouteKind.QUEUE_FILTER) {
                queueFilters.put(r.route().target(), r.route().filter());
            }
        }
        boolean capture = layers.contains(Layer.CAPTURE);

        Map<String, QueueAgg> queues = new TreeMap<>();
        for (QueueSnapshot s : snapshots.forCluster(clusterId)) {
            if (internal(s.address()) || internal(s.queueName()) || temporaryQueues.contains(s.queueName())) {
                continue;
            }
            if (captureOwned(s.queueName()) && !capture) {
                continue;
            }
            queues.computeIfAbsent(s.queueName(), name -> new QueueAgg(name, s.address()))
                    .add(s, nodeNames.get(s.nodeId()));
        }
        Instant windowStart = now.minus(tierC.multipliedBy(3));
        Map<String, SubjectRate> added =
                metrics.latestRateWithTimeBySubject(clusterId, "messagesAdded", windowStart, now.plusSeconds(1));
        Map<String, SubjectRate> acked =
                metrics.latestRateWithTimeBySubject(clusterId, "messagesAcked", windowStart, now.plusSeconds(1));

        Map<String, ClientAgg> producers = new TreeMap<>();
        Map<String, ClientAgg> consumers = new TreeMap<>();
        for (StoredEdge stored : store.edges(clusterId)) {
            Edge e = stored.edge();
            if (internal(e.address()) || internal(e.queue())) {
                continue;
            }
            if ((captureOwned(e.queue()) || captureOwned(e.address())) && !capture) {
                continue;
            }
            String label = label(e, query.groupBy());
            String nodeName = nodeNames.get(stored.nodeId());
            if (e.kind() == Kind.PRODUCE) {
                producers.computeIfAbsent(label, ClientAgg::new).add(stored, nodeName, e.address());
                continue;
            }
            if (temporaryQueues.contains(e.queue())) {
                if (!layers.contains(Layer.TEMPORARY)) {
                    continue;
                }
                // One node per client for all its temporary queues: each is short-lived and uniquely
                // named, and drawn one by one they would bury the paths an operator came for.
                String collapsed = TEMPORARY_PREFIX + label;
                queues.computeIfAbsent(collapsed, name -> new QueueAgg(name, null, NodeRole.TEMPORARY))
                        .temporary
                        .add(e.queue());
                consumers.computeIfAbsent(label, ClientAgg::new).add(stored, nodeName, collapsed);
                continue;
            }
            consumers.computeIfAbsent(label, ClientAgg::new).add(stored, nodeName, e.queue());
            if (!e.queue().isEmpty()) {
                // A queue can be consumed before the slow queue sweep has seen it.
                queues.computeIfAbsent(e.queue(), name -> new QueueAgg(name, e.address()));
            }
        }

        Map<String, HopAgg> hops = new TreeMap<>();
        Map<List<Object>, RoutingAgg> diverts = new LinkedHashMap<>();
        Map<List<Object>, RoutingAgg> bridges = new LinkedHashMap<>();
        String deadLetter = null;
        String expiry = null;
        for (StoredRoute stored : routes) {
            Route r = stored.route();
            String nodeName = nodeNames.get(stored.nodeId());
            switch (r.kind()) {
                case STORE_AND_FORWARD -> {
                    if (layers.contains(Layer.CLUSTER)) {
                        hops.computeIfAbsent(r.name(), name -> new HopAgg(name, r.target()))
                                .add(r, stored.sampledAt(), nodeName);
                    }
                }
                case DIVERT -> {
                    if (layers.contains(Layer.DIVERTS) && (capture || !captureOwned(r.name()))) {
                        diverts.computeIfAbsent(
                                        List.of(
                                                r.name(),
                                                r.source(),
                                                r.target(),
                                                r.exclusive(),
                                                String.valueOf(r.filter())),
                                        k -> new RoutingAgg(r))
                                .add(r, stored.sampledAt(), stored.nodeId());
                    }
                }
                case BRIDGE -> {
                    if (layers.contains(Layer.BRIDGES)) {
                        bridges.computeIfAbsent(List.of(r.name(), r.source(), r.target()), k -> new RoutingAgg(r))
                                .add(r, stored.sampledAt(), stored.nodeId());
                    }
                }
                case DEAD_LETTER -> deadLetter = r.target();
                case EXPIRY -> expiry = r.target();
                default -> {
                    // Temporary and filtered queues were read above.
                }
            }
        }
        for (HopAgg hop : hops.values()) {
            QueueAgg q =
                    queues.computeIfAbsent(hop.queue, name -> new QueueAgg(name, null, NodeRole.STORE_AND_FORWARD));
            q.ownRate = hop.rate;
            q.ownAsOf = hop.asOf;
            q.brokerNodes.addAll(hop.brokerNodes);
        }

        List<Path> all = paths(queues, producers);
        Comparator<Path> ranking = Comparator.comparingInt(
                        (Path p) -> bucket(score(p, query, added, acked, queues, producers, consumers)))
                .reversed()
                .thenComparing(Path::key);
        List<Path> ranked = all.stream().sorted(ranking).toList();

        FlowFocusView focusView = null;
        List<Path> chosen;
        if (query.focus() != null) {
            Set<Path> reach = neighbourhood(query.focus(), query.hops(), all, producers, consumers);
            chosen =
                    ranked.stream().filter(reach::contains).limit(query.limit()).toList();
            focusView = new FlowFocusView(
                    query.focus().kind().name().toLowerCase(Locale.ROOT),
                    query.focus().name(),
                    query.hops(),
                    !reach.isEmpty());
        } else {
            chosen = ranked.stream().limit(query.limit()).toList();
        }

        int sampledNodes = samples.size();
        Freshness fresh = new Freshness(now, sampleInterval, tierC);
        Drawing drawing = new Drawing();
        draw(chosen, queues, producers, consumers, added, queueFilters, fresh, drawing);
        Set<String> knownAddresses = new TreeSet<>();
        queues.values().forEach(q -> {
            if (q.address != null) knownAddresses.add(q.address);
        });
        producers.values().forEach(c -> knownAddresses.addAll(c.targets.keySet()));
        drawDiverts(diverts.values(), sampledNodes, fresh, drawing, queues);
        drawBridges(bridges.values(), sampledNodes, knownAddresses, fresh, drawing);
        drawHops(hops.values(), nodeNamesByArtemisId, fresh, drawing);
        List<String> assumptions = drawWildcards(drawing);
        if (layers.contains(Layer.DEAD_LETTER)) {
            drawFailureRoutes(drawing, queues, deadLetter, EdgeKind.DEAD_LETTER, NodeRole.DEAD_LETTER);
            drawFailureRoutes(drawing, queues, expiry, EdgeKind.EXPIRY, NodeRole.EXPIRY);
        }

        List<FlowBrokerNodeView> brokerNodes = samples.stream()
                .map(s -> brokerNode(s, nodeNames.get(s.nodeId())))
                .sorted(Comparator.comparing(FlowBrokerNodeView::name, Comparator.nullsLast(String::compareTo)))
                .toList();
        Instant sampledAt = samples.stream()
                .map(NodeSample::sampledAt)
                .max(Instant::compareTo)
                .orElse(null);

        return new FlowGraphView(
                drawing.nodes(),
                drawing.edges(),
                kpis(queues, producers, consumers, added, acked, brokerNodes, diverts, bridges, sampledNodes),
                new FlowTotals(all.size(), chosen.size(), query.limit(), query.clamped()),
                focusView,
                sampledAt,
                samples.isEmpty(),
                sampleInterval.toSeconds(),
                layers.stream().map(Enum::name).sorted().toList(),
                assumptions,
                brokerNodes);
    }

    private static List<Path> paths(Map<String, QueueAgg> queues, Map<String, ClientAgg> producers) {
        Set<Path> paths = new LinkedHashSet<>();
        Set<String> routed = new TreeSet<>();
        for (QueueAgg q : queues.values()) {
            paths.add(new Path(q.address, q.name));
            if (q.address != null) {
                routed.add(q.address);
            }
        }
        for (ClientAgg p : producers.values()) {
            for (String address : p.targets.keySet()) {
                if (!routed.contains(address)) {
                    paths.add(new Path(address, null));
                }
            }
        }
        return List.copyOf(paths);
    }

    private static Double score(
            Path p,
            FlowQuery query,
            Map<String, SubjectRate> added,
            Map<String, SubjectRate> acked,
            Map<String, QueueAgg> queues,
            Map<String, ClientAgg> producers,
            Map<String, ClientAgg> consumers) {
        if (p.queue() == null) {
            // No queue: the only measure is what producers send to the address.
            return query.rank() == FlowQuery.Rank.IN ? targetRate(producers, p.address()) : null;
        }
        QueueAgg q = queues.get(p.queue());
        if (q.role == NodeRole.STORE_AND_FORWARD) {
            return query.rank() == FlowQuery.Rank.BACKLOG ? null : q.ownRate;
        }
        if (q.role == NodeRole.TEMPORARY) {
            return query.rank() == FlowQuery.Rank.BACKLOG ? null : targetRate(consumers, q.name);
        }
        return switch (query.rank()) {
            case IN -> rate(added.get(p.queue()));
            case OUT -> rate(acked.get(p.queue()));
            case BACKLOG -> q.messageCount == null ? null : q.messageCount.doubleValue();
        };
    }

    private static Double targetRate(Map<String, ClientAgg> clients, String target) {
        Double sum = null;
        for (ClientAgg c : clients.values()) {
            TargetAgg t = c.targets.get(target);
            if (t != null && t.rate != null) {
                sum = (sum == null ? 0 : sum) + t.rate;
            }
        }
        return sum;
    }

    /**
     * Half-decade buckets of a score, so paths whose rates differ slightly keep their order between
     * refreshes without any memory of the last one. Unknown ranks below zero, so a path still being
     * measured never displaces a measured one and never reads as idle.
     */
    static int bucket(Double score) {
        if (score == null) {
            return Integer.MIN_VALUE;
        }
        if (score <= 0) {
            return Integer.MIN_VALUE + 1;
        }
        return (int) Math.floor(Math.log10(score) * 2);
    }

    private static Set<Path> neighbourhood(
            Focus focus, int hops, List<Path> all, Map<String, ClientAgg> producers, Map<String, ClientAgg> consumers) {
        Set<Path> reach = new LinkedHashSet<>();
        for (Path p : all) {
            boolean hit =
                    switch (focus.kind()) {
                        case QUEUE -> focus.name().equals(p.queue());
                        case ADDRESS -> focus.name().equals(p.address());
                        case CLIENT -> touches(producers.get(focus.name()), consumers.get(focus.name()), p);
                    };
            if (hit) {
                reach.add(p);
            }
        }
        for (int hop = 1; hop < hops && !reach.isEmpty(); hop++) {
            Set<String> addresses = new TreeSet<>();
            Set<String> queueNames = new TreeSet<>();
            reach.forEach(p -> {
                if (p.address() != null) {
                    addresses.add(p.address());
                }
                if (p.queue() != null) {
                    queueNames.add(p.queue());
                }
            });
            List<ClientAgg> clients = new ArrayList<>();
            producers.values().stream()
                    .filter(c -> c.targets.keySet().stream().anyMatch(addresses::contains))
                    .forEach(clients::add);
            consumers.values().stream()
                    .filter(c -> c.targets.keySet().stream().anyMatch(queueNames::contains))
                    .forEach(clients::add);
            Set<Path> next = new LinkedHashSet<>(reach);
            for (Path p : all) {
                if (p.address() != null && addresses.contains(p.address())) {
                    next.add(p);
                }
                for (ClientAgg c : clients) {
                    if (touches(producers.get(c.label), consumers.get(c.label), p)) {
                        next.add(p);
                    }
                }
            }
            reach = next;
        }
        return reach;
    }

    private static boolean touches(ClientAgg producer, ClientAgg consumer, Path p) {
        return (producer != null && p.address() != null && producer.targets.containsKey(p.address()))
                || (consumer != null && p.queue() != null && consumer.targets.containsKey(p.queue()));
    }

    // ---- drawing ----------------------------------------------------------------------------------

    /** The nodes and edges being drawn, keyed so that routing layers can add to what paths drew. */
    private static final class Drawing {
        final Map<String, FlowNodeView> nodes = new LinkedHashMap<>();
        final Map<String, FlowEdgeView> edges = new LinkedHashMap<>();
        final Set<String> addresses = new TreeSet<>();
        final Set<String> queues = new TreeSet<>();

        List<FlowNodeView> nodes() {
            return nodes.values().stream()
                    .sorted(Comparator.comparing(FlowNodeView::kind).thenComparing(FlowNodeView::id))
                    .toList();
        }

        List<FlowEdgeView> edges() {
            return edges.values().stream()
                    .sorted(Comparator.comparing(FlowEdgeView::id))
                    .toList();
        }

        void node(FlowNodeView view) {
            nodes.putIfAbsent(view.id(), view);
        }

        void edge(FlowEdgeView view) {
            if (nodes.containsKey(view.source()) && nodes.containsKey(view.target())) {
                edges.putIfAbsent(view.id(), view);
            }
        }
    }

    private static void draw(
            List<Path> chosen,
            Map<String, QueueAgg> queues,
            Map<String, ClientAgg> producers,
            Map<String, ClientAgg> consumers,
            Map<String, SubjectRate> added,
            Map<String, String> queueFilters,
            Fresh fresh,
            Drawing drawing) {
        for (Path p : chosen) {
            if (p.address() != null) {
                drawing.addresses.add(p.address());
            }
            if (p.queue() != null) {
                drawing.queues.add(p.queue());
            }
        }

        for (String address : drawing.addresses) {
            addressNode(drawing, address, queues);
        }

        for (String name : drawing.queues) {
            QueueAgg q = queues.get(name);
            drawing.node(new FlowNodeView(
                    queueId(name),
                    NodeKind.QUEUE,
                    q.role != null ? q.role : captureOwned(name) ? NodeRole.CAPTURE : null,
                    queueLabel(q),
                    q.role == NodeRole.TEMPORARY ? q.temporary.size() : null,
                    q.messageCount,
                    q.consumerCount,
                    q.routingType == null ? List.of() : List.of(q.routingType),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.copyOf(q.brokerNodes),
                    q.noConsumer() ? List.of(Fault.NO_CONSUMER) : List.of()));
            if (q.address == null) {
                continue;
            }
            SubjectRate rate = added.get(name);
            drawing.edge(new FlowEdgeView(
                    "route:" + q.address + "->" + name,
                    EdgeKind.ROUTE,
                    addressId(q.address),
                    queueId(name),
                    rate(rate),
                    RateSource.QUEUE_METRIC,
                    rate == null ? null : rate.asOf(),
                    rate != null && rate.span().compareTo(fresh.sampleInterval().multipliedBy(2)) > 0
                            ? rate.span().toSeconds()
                            : null,
                    rate != null && fresh.olderThan(rate.asOf(), fresh.tierC()),
                    delivery(q.routingType),
                    null,
                    null,
                    queueFilters.get(name),
                    null,
                    false,
                    null,
                    null,
                    captureOwned(name),
                    List.of()));
        }

        drawClients(producers, drawing.addresses, NodeKind.PRODUCER, fresh, drawing);
        drawClients(consumers, drawing.queues, NodeKind.CONSUMER, fresh, drawing);
    }

    private static void addressNode(Drawing drawing, String address, Map<String, QueueAgg> queues) {
        Set<String> routingTypes = new TreeSet<>();
        Set<String> brokerNodes = new TreeSet<>();
        queues.values().stream().filter(q -> address.equals(q.address)).forEach(q -> {
            if (q.routingType != null) {
                routingTypes.add(q.routingType);
            }
            brokerNodes.addAll(q.brokerNodes);
        });
        boolean anonymous = ANONYMOUS.equals(address);
        drawing.node(new FlowNodeView(
                addressId(address),
                NodeKind.ADDRESS,
                anonymous ? NodeRole.ANONYMOUS : captureOwned(address) ? NodeRole.CAPTURE : null,
                anonymous ? "anonymous producers — address chosen per message" : address,
                null,
                null,
                null,
                List.copyOf(routingTypes),
                List.of(),
                List.of(),
                List.of(),
                List.copyOf(brokerNodes),
                List.of()));
    }

    private static String queueLabel(QueueAgg q) {
        if (q.role == NodeRole.TEMPORARY) {
            return "temporary queues ×" + q.temporary.size();
        }
        return q.name;
    }

    private static void drawClients(
            Map<String, ClientAgg> clients, Set<String> shownTargets, NodeKind kind, Fresh fresh, Drawing drawing) {
        boolean producing = kind == NodeKind.PRODUCER;
        for (ClientAgg c : clients.values()) {
            List<Map.Entry<String, TargetAgg>> shown = c.targets.entrySet().stream()
                    .filter(t -> shownTargets.contains(t.getKey()))
                    .toList();
            if (shown.isEmpty()) {
                continue;
            }
            String id = (producing ? "producer:" : "consumer:") + c.label;
            boolean stalled = shown.stream().anyMatch(t -> t.getValue().stalled);
            drawing.node(new FlowNodeView(
                    id,
                    kind,
                    null,
                    c.label,
                    c.members,
                    null,
                    null,
                    List.of(),
                    List.copyOf(c.protocols),
                    List.copyOf(c.hosts),
                    List.copyOf(c.users),
                    List.copyOf(c.brokerNodes),
                    stalled ? List.of(Fault.STALLED) : List.of()));
            for (Map.Entry<String, TargetAgg> t : shown) {
                TargetAgg agg = t.getValue();
                String target = producing ? addressId(t.getKey()) : queueId(t.getKey());
                drawing.edge(new FlowEdgeView(
                        (producing ? "produce:" : "consume:") + c.label + "->" + t.getKey(),
                        producing ? EdgeKind.PRODUCE : EdgeKind.CONSUME,
                        producing ? id : target,
                        producing ? target : id,
                        agg.rate,
                        RateSource.SAMPLER,
                        agg.asOf,
                        null,
                        agg.asOf != null && fresh.olderThan(agg.asOf, fresh.sampleInterval()),
                        null,
                        agg.members,
                        null,
                        null,
                        null,
                        false,
                        null,
                        null,
                        false,
                        agg.stalled ? List.of(Fault.STALLED) : List.of()));
            }
        }
    }

    /**
     * A divert from a shown address. Its target address is drawn even when none of its queues made the
     * bound, so the operator sees where the messages go. An exclusive divert without a filter takes
     * every message before the address's own queues see it, which the route edges then say.
     */
    private static void drawDiverts(
            java.util.Collection<RoutingAgg> diverts,
            int sampledNodes,
            Fresh fresh,
            Drawing drawing,
            Map<String, QueueAgg> queues) {
        for (RoutingAgg d : diverts) {
            Route r = d.route;
            if (!drawing.addresses.contains(r.source())) {
                continue;
            }
            addressNode(drawing, r.target(), queues);
            List<Fault> faults = d.nodes.size() < sampledNodes ? List.of(Fault.PARTIAL_PRESENCE) : List.of();
            drawing.edge(new FlowEdgeView(
                    "divert:" + r.name() + ":" + r.source() + "->" + r.target(),
                    EdgeKind.DIVERT,
                    addressId(r.source()),
                    addressId(r.target()),
                    null,
                    RateSource.NONE,
                    d.asOf,
                    null,
                    false,
                    null,
                    null,
                    r.exclusive(),
                    r.filter(),
                    r.transformer(),
                    false,
                    d.nodes.size(),
                    sampledNodes,
                    captureOwned(r.name()),
                    faults));
            if (r.exclusive() && (r.filter() == null || r.filter().isBlank())) {
                for (String edgeId : List.copyOf(drawing.edges.keySet())) {
                    FlowEdgeView e = drawing.edges.get(edgeId);
                    if (e.kind() == EdgeKind.ROUTE && e.source().equals(addressId(r.source()))) {
                        drawing.edges.put(edgeId, bypassed(e));
                    }
                }
            }
        }
    }

    private static void drawBridges(
            java.util.Collection<RoutingAgg> bridges,
            int sampledNodes,
            Set<String> knownAddresses,
            Fresh fresh,
            Drawing drawing) {
        for (RoutingAgg b : bridges) {
            Route r = b.route;
            if (!drawing.queues.contains(r.source())) {
                continue;
            }
            String target;
            if (knownAddresses.contains(r.target())) {
                target = addressId(r.target());
                drawing.node(new FlowNodeView(
                        target,
                        NodeKind.ADDRESS,
                        null,
                        r.target(),
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()));
            } else {
                target = "remote:bridge:" + r.target();
                drawing.node(new FlowNodeView(
                        target,
                        NodeKind.REMOTE,
                        NodeRole.BRIDGE_TARGET,
                        r.target(),
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()));
            }
            List<Fault> faults = new ArrayList<>();
            if (b.connectedOn < b.nodes.size()) {
                faults.add(Fault.BRIDGE_DOWN);
            }
            if (b.nodes.size() < sampledNodes) {
                faults.add(Fault.PARTIAL_PRESENCE);
            }
            drawing.edge(new FlowEdgeView(
                    "bridge:" + r.name(),
                    EdgeKind.BRIDGE,
                    queueId(r.source()),
                    target,
                    b.rate,
                    RateSource.SAMPLER,
                    b.asOf,
                    null,
                    b.asOf != null && fresh.olderThan(b.asOf, fresh.sampleInterval()),
                    null,
                    null,
                    null,
                    r.filter(),
                    r.transformer(),
                    false,
                    b.nodes.size(),
                    sampledNodes,
                    false,
                    List.copyOf(faults)));
        }
    }

    private static void drawHops(
            java.util.Collection<HopAgg> hops, Map<String, String> nodeNamesByArtemisId, Fresh fresh, Drawing drawing) {
        for (HopAgg hop : hops) {
            if (!drawing.queues.contains(hop.queue)) {
                continue;
            }
            String nodeName = nodeNamesByArtemisId.get(hop.targetNodeId);
            String target = "remote:node:" + hop.targetNodeId;
            drawing.node(new FlowNodeView(
                    target,
                    NodeKind.REMOTE,
                    NodeRole.CLUSTER_NODE,
                    nodeName != null ? nodeName : "node " + shortId(hop.targetNodeId),
                    null,
                    null,
                    null,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of()));
            drawing.edge(new FlowEdgeView(
                    "hop:" + hop.queue,
                    EdgeKind.CLUSTER_HOP,
                    queueId(hop.queue),
                    target,
                    hop.rate,
                    RateSource.SAMPLER,
                    hop.asOf,
                    null,
                    hop.asOf != null && fresh.olderThan(hop.asOf, fresh.sampleInterval()),
                    null,
                    null,
                    null,
                    null,
                    null,
                    false,
                    null,
                    null,
                    false,
                    List.of()));
        }
    }

    /** Joins each shown concrete address to the shown wildcard addresses it matches, and says what that assumes. */
    private static List<String> drawWildcards(Drawing drawing) {
        List<String> wildcards =
                drawing.addresses.stream().filter(WildcardMatcher::isWildcard).toList();
        boolean drew = false;
        for (String wildcard : wildcards) {
            for (String address : drawing.addresses) {
                if (ANONYMOUS.equals(address) || WildcardMatcher.isWildcard(address)) {
                    continue;
                }
                if (WildcardMatcher.matches(wildcard, address)) {
                    drawing.edge(new FlowEdgeView(
                            "wildcard:" + address + "->" + wildcard,
                            EdgeKind.WILDCARD,
                            addressId(address),
                            addressId(wildcard),
                            null,
                            RateSource.NONE,
                            null,
                            null,
                            false,
                            null,
                            null,
                            null,
                            null,
                            null,
                            false,
                            null,
                            null,
                            false,
                            List.of()));
                    drew = true;
                }
            }
        }
        return drew ? List.of(WILDCARD_ASSUMPTION) : List.of();
    }

    private static void drawFailureRoutes(
            Drawing drawing, Map<String, QueueAgg> queues, String address, EdgeKind kind, NodeRole role) {
        if (address == null || address.isBlank()) {
            return;
        }
        List<String> sources = drawing.queues.stream()
                .filter(name -> {
                    QueueAgg q = queues.get(name);
                    return q.role == null && !address.equals(q.address);
                })
                .toList();
        if (sources.isEmpty()) {
            return;
        }
        drawing.nodes.remove(addressId(address));
        drawing.node(new FlowNodeView(
                addressId(address),
                NodeKind.ADDRESS,
                role,
                address,
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()));
        for (String name : sources) {
            drawing.edge(new FlowEdgeView(
                    kind.name().toLowerCase(Locale.ROOT) + ":" + name,
                    kind,
                    queueId(name),
                    addressId(address),
                    null,
                    RateSource.NONE,
                    null,
                    null,
                    false,
                    null,
                    null,
                    null,
                    null,
                    null,
                    false,
                    null,
                    null,
                    false,
                    List.of()));
        }
    }

    private static FlowEdgeView bypassed(FlowEdgeView e) {
        return new FlowEdgeView(
                e.id(),
                e.kind(),
                e.source(),
                e.target(),
                e.rate(),
                e.rateSource(),
                e.asOf(),
                e.averagedOverSeconds(),
                e.stale(),
                e.delivery(),
                e.members(),
                e.exclusive(),
                e.filter(),
                e.transformer(),
                true,
                e.presentOn(),
                e.presentOf(),
                e.studio(),
                e.faults());
    }

    private static FlowKpis kpis(
            Map<String, QueueAgg> queues,
            Map<String, ClientAgg> producers,
            Map<String, ClientAgg> consumers,
            Map<String, SubjectRate> added,
            Map<String, SubjectRate> acked,
            List<FlowBrokerNodeView> brokerNodes,
            Map<List<Object>, RoutingAgg> diverts,
            Map<List<Object>, RoutingAgg> bridges,
            int sampledNodes) {
        Double in = null;
        Double out = null;
        long backlog = 0;
        int faults = 0;
        for (QueueAgg q : queues.values()) {
            if (q.role != null) {
                continue;
            }
            SubjectRate a = added.get(q.name);
            SubjectRate k = acked.get(q.name);
            if (a != null) {
                in = (in == null ? 0 : in) + a.rate();
            }
            if (k != null) {
                out = (out == null ? 0 : out) + k.rate();
            }
            backlog += q.messageCount == null ? 0 : q.messageCount;
            faults += q.noConsumer() ? 1 : 0;
        }
        for (ClientAgg c : consumers.values()) {
            faults += (int) c.targets.values().stream().filter(t -> t.stalled).count();
        }
        for (RoutingAgg d : diverts.values()) {
            faults += d.nodes.size() < sampledNodes ? 1 : 0;
        }
        for (RoutingAgg b : bridges.values()) {
            faults += b.connectedOn < b.nodes.size() || b.nodes.size() < sampledNodes ? 1 : 0;
        }
        faults += (int) brokerNodes.stream()
                .filter(n -> n.state() != NodeSampleState.OK)
                .count();
        Set<String> clients = new TreeSet<>(producers.keySet());
        clients.addAll(consumers.keySet());
        return new FlowKpis(in, out, backlog, clients.size(), faults);
    }

    private static FlowBrokerNodeView brokerNode(NodeSample s, String name) {
        NodeSampleState state = state(s.errorKind());
        String message =
                switch (state) {
                    case OK -> null;
                    case UNREACHABLE -> "This node did not answer the latest sweep, so its clients are not shown.";
                    case PERMISSION_DENIED ->
                        "The broker refused to list producers or consumers for Studio's user, so this node's clients"
                                + " are not shown.";
                    case COUNTER_UNAVAILABLE ->
                        "This broker does not report the counters a rate needs; its rates are unavailable, not zero.";
                    case ROUTING_UNAVAILABLE ->
                        "Clients on this node are shown, but its diverts, bridges or queue details could not be read"
                                + " (" + s.error() + "), so routing on it may be missing.";
                    case FAILED -> s.error() == null ? "The latest sweep of this node failed." : s.error();
                };
        return new FlowBrokerNodeView(
                s.nodeId().toString(),
                name,
                state,
                message,
                s.sampledAt(),
                s.producersSeen(),
                s.producersTotal(),
                s.consumersSeen(),
                s.consumersTotal(),
                s.producersTotal() > s.producersSeen() || s.consumersTotal() > s.consumersSeen(),
                state == NodeSampleState.PERMISSION_DENIED ? LIST_ACCESS_SNIPPET : null);
    }

    private static NodeSampleState state(String errorKind) {
        if (errorKind == null) {
            return NodeSampleState.OK;
        }
        return switch (errorKind) {
            case "UNREACHABLE", "TLS_FAILED", "WRONG_PATH", "NOT_ARTEMIS" -> NodeSampleState.UNREACHABLE;
            case "UNAUTHORIZED", "PERMISSION_DENIED" -> NodeSampleState.PERMISSION_DENIED;
            case "COUNTER_UNAVAILABLE" -> NodeSampleState.COUNTER_UNAVAILABLE;
            case "ROUTING_UNAVAILABLE" -> NodeSampleState.ROUTING_UNAVAILABLE;
            default -> NodeSampleState.FAILED;
        };
    }

    /** The label a client is grouped under, falling back through the identity parts the grouping allows. */
    static String label(Edge e, GroupBy groupBy) {
        return switch (groupBy) {
            case CLIENT_ID -> firstNonBlank(e.clientId(), e.user(), e.remoteHost());
            case USER -> firstNonBlank(e.user(), e.remoteHost());
            case HOST -> firstNonBlank(e.remoteHost());
        };
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return UNIDENTIFIED;
    }

    /** Broker-internal addresses and queues, which are not the operator's resources. */
    static boolean internal(String name) {
        return name != null && (name.startsWith("$.") || name.equals("activemq.notifications"));
    }

    /** A divert, address or queue Studio's message capture owns (ADR-0079). */
    static boolean captureOwned(String name) {
        return name != null && name.startsWith(DivertOperations.CAPTURE_PREFIX);
    }

    private static Delivery delivery(String routingType) {
        if ("MULTICAST".equalsIgnoreCase(routingType)) {
            return Delivery.COPY;
        }
        return "ANYCAST".equalsIgnoreCase(routingType) ? Delivery.SHARED : null;
    }

    private static Double rate(SubjectRate r) {
        return r == null ? null : r.rate();
    }

    private static String shortId(String id) {
        return id.length() > 8 ? id.substring(0, 8) : id;
    }

    private static String addressId(String address) {
        return "address:" + address;
    }

    private static String queueId(String queue) {
        return "queue:" + queue;
    }

    private record Path(String address, String queue) {
        String key() {
            return queue == null ? "a:" + address : "q:" + queue;
        }
    }

    private interface Fresh {
        Duration sampleInterval();

        Duration tierC();

        boolean olderThan(Instant asOf, Duration interval);
    }

    private record Freshness(Instant now, Duration sampleInterval, Duration tierC) implements Fresh {
        @Override
        public boolean olderThan(Instant asOf, Duration interval) {
            return Duration.between(asOf, now).compareTo(interval.multipliedBy(3)) > 0;
        }
    }

    private static final class QueueAgg {
        final String name;
        /** Null for a queue no address routes to in the drawing: store-and-forward or collapsed temporary. */
        final String address;

        final NodeRole role;
        String routingType;
        Long messageCount;
        Long consumerCount;
        Double ownRate;
        Instant ownAsOf;
        final Set<String> brokerNodes = new TreeSet<>();
        final Set<String> temporary = new TreeSet<>();

        QueueAgg(String name, String address) {
            this(name, address == null || address.isBlank() ? null : address, null);
        }

        QueueAgg(String name, String address, NodeRole role) {
            this.name = name;
            this.address = address;
            this.role = role;
        }

        void add(QueueSnapshot s, String nodeName) {
            routingType = s.routingType();
            messageCount = (messageCount == null ? 0 : messageCount) + s.messageCount();
            consumerCount = (consumerCount == null ? 0 : consumerCount) + s.consumerCount();
            if (nodeName != null) {
                brokerNodes.add(nodeName);
            }
        }

        boolean noConsumer() {
            return role == null
                    && messageCount != null
                    && messageCount > 0
                    && consumerCount != null
                    && consumerCount == 0;
        }
    }

    private static final class TargetAgg {
        Double rate;
        int members;
        boolean stalled;
        Instant asOf;
    }

    private static final class ClientAgg {
        final String label;
        int members;
        final Set<String> protocols = new TreeSet<>();
        final Set<String> hosts = new TreeSet<>();
        final Set<String> users = new TreeSet<>();
        final Set<String> brokerNodes = new TreeSet<>();
        /** Keyed by address for a producer, by queue for a consumer. */
        final Map<String, TargetAgg> targets = new TreeMap<>();

        ClientAgg(String label) {
            this.label = label;
        }

        void add(StoredEdge stored, String nodeName, String target) {
            Edge e = stored.edge();
            members += e.memberCount();
            addIfPresent(protocols, e.protocol());
            addIfPresent(hosts, e.remoteHost());
            addIfPresent(users, e.user());
            addIfPresent(brokerNodes, nodeName);
            TargetAgg t = targets.computeIfAbsent(target, k -> new TargetAgg());
            if (e.rate() != null) {
                t.rate = (t.rate == null ? 0 : t.rate) + e.rate();
            }
            t.members += e.memberCount();
            t.stalled |= e.stalled();
            t.asOf = t.asOf == null || stored.sampledAt().isAfter(t.asOf) ? stored.sampledAt() : t.asOf;
        }

        private static void addIfPresent(Set<String> set, String value) {
            if (value != null && !value.isBlank()) {
                set.add(value);
            }
        }
    }

    /** A divert or bridge merged across the nodes it is deployed on. */
    private static final class RoutingAgg {
        final Route route;
        final Set<UUID> nodes = new TreeSet<>();
        int connectedOn;
        Double rate;
        Instant asOf;

        RoutingAgg(Route route) {
            this.route = route;
        }

        void add(Route r, Instant sampledAt, UUID nodeId) {
            if (nodes.add(nodeId) && r.connected()) {
                connectedOn++;
            }
            if (r.rate() != null) {
                rate = (rate == null ? 0 : rate) + r.rate();
            }
            asOf = asOf == null || sampledAt.isAfter(asOf) ? sampledAt : asOf;
        }
    }

    /** One store-and-forward queue, toward the node it is named for. */
    private static final class HopAgg {
        final String queue;
        final String targetNodeId;
        final Set<String> brokerNodes = new TreeSet<>();
        Double rate;
        Instant asOf;

        HopAgg(String queue, String targetNodeId) {
            this.queue = queue;
            this.targetNodeId = Objects.requireNonNullElse(targetNodeId, "");
        }

        void add(Route r, Instant sampledAt, String nodeName) {
            if (nodeName != null) {
                brokerNodes.add(nodeName);
            }
            if (r.rate() != null) {
                rate = (rate == null ? 0 : rate) + r.rate();
            }
            asOf = asOf == null || sampledAt.isAfter(asOf) ? sampledAt : asOf;
        }
    }
}
