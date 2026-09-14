package io.github.sudoitir.artemisstudio.feature.flow;

import io.github.sudoitir.artemisstudio.feature.flow.ClientEdges.Edge;
import io.github.sudoitir.artemisstudio.feature.flow.ClientEdges.Kind;
import io.github.sudoitir.artemisstudio.feature.flow.FlowQuery.Focus;
import io.github.sudoitir.artemisstudio.feature.flow.FlowQuery.GroupBy;
import io.github.sudoitir.artemisstudio.feature.flow.FlowStore.NodeSample;
import io.github.sudoitir.artemisstudio.feature.flow.FlowStore.StoredEdge;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * consuming from that queue, or an address produced to that has no queue yet. Paths are what the
 * bound counts and what the ranking orders.
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
                  </match>
               </role-access>
            </authorisation>""";

    private static final String UNIDENTIFIED = "unidentified client";

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
        Map<UUID, String> nodeNames = new HashMap<>();
        for (ClusterNode n : directory.nodes(clusterId)) {
            nodeNames.put(n.getId(), n.getName());
        }

        Map<String, QueueAgg> queues = new TreeMap<>();
        for (QueueSnapshot s : snapshots.forCluster(clusterId)) {
            if (!internal(s.address()) && !internal(s.queueName())) {
                queues.computeIfAbsent(s.queueName(), name -> new QueueAgg(name, s.address()))
                        .add(s, nodeNames.get(s.nodeId()));
            }
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
            Map<String, ClientAgg> side = e.kind() == Kind.PRODUCE ? producers : consumers;
            side.computeIfAbsent(label(e, query.groupBy()), ClientAgg::new).add(stored, nodeNames.get(stored.nodeId()));
            if (e.kind() == Kind.CONSUME && !e.queue().isEmpty()) {
                // A queue can be consumed before the slow queue sweep has seen it.
                queues.computeIfAbsent(e.queue(), name -> new QueueAgg(name, e.address()));
            }
        }
        List<NodeSample> samples = store.nodeSamples(clusterId);

        List<Path> all = paths(queues, producers);
        Comparator<Path> ranking = Comparator.comparingInt(
                        (Path p) -> bucket(score(p, query, added, acked, queues, producers)))
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

        Freshness fresh = new Freshness(now, sampleInterval, tierC);
        List<FlowNodeView> nodes = new ArrayList<>();
        List<FlowEdgeView> edges = new ArrayList<>();
        draw(chosen, queues, producers, consumers, added, fresh, nodes, edges);

        List<FlowBrokerNodeView> brokerNodes = samples.stream()
                .map(s -> brokerNode(s, nodeNames.get(s.nodeId())))
                .sorted(Comparator.comparing(FlowBrokerNodeView::name, Comparator.nullsLast(String::compareTo)))
                .toList();
        Instant sampledAt = samples.stream()
                .map(NodeSample::sampledAt)
                .max(Instant::compareTo)
                .orElse(null);

        return new FlowGraphView(
                nodes,
                edges,
                kpis(queues, producers, consumers, added, acked, brokerNodes),
                new FlowTotals(all.size(), chosen.size(), query.limit(), query.clamped()),
                focusView,
                sampledAt,
                samples.isEmpty(),
                sampleInterval.toSeconds(),
                brokerNodes);
    }

    private static List<Path> paths(Map<String, QueueAgg> queues, Map<String, ClientAgg> producers) {
        Set<Path> paths = new LinkedHashSet<>();
        Set<String> routed = new TreeSet<>();
        for (QueueAgg q : queues.values()) {
            paths.add(new Path(q.address, q.name));
            routed.add(q.address);
        }
        for (ClientAgg p : producers.values()) {
            for (String address : p.targets.keySet()) {
                if (!address.isEmpty() && !routed.contains(address)) {
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
            Map<String, ClientAgg> producers) {
        if (p.queue() == null) {
            // No queue: the only measure is what producers send to the address.
            Double sum = null;
            for (ClientAgg c : producers.values()) {
                TargetAgg t = c.targets.get(p.address());
                if (t != null && t.rate != null) {
                    sum = (sum == null ? 0 : sum) + t.rate;
                }
            }
            return query.rank() == FlowQuery.Rank.IN ? sum : null;
        }
        return switch (query.rank()) {
            case IN -> rate(added.get(p.queue()));
            case OUT -> rate(acked.get(p.queue()));
            case BACKLOG -> {
                Long count = queues.get(p.queue()).messageCount;
                yield count == null ? null : count.doubleValue();
            }
        };
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
                addresses.add(p.address());
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
                if (addresses.contains(p.address())) {
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
        return (producer != null && producer.targets.containsKey(p.address()))
                || (consumer != null && p.queue() != null && consumer.targets.containsKey(p.queue()));
    }

    private static void draw(
            List<Path> chosen,
            Map<String, QueueAgg> queues,
            Map<String, ClientAgg> producers,
            Map<String, ClientAgg> consumers,
            Map<String, SubjectRate> added,
            Freshness fresh,
            List<FlowNodeView> nodes,
            List<FlowEdgeView> edges) {
        Set<String> addresses = new TreeSet<>();
        Set<String> queueNames = new TreeSet<>();
        for (Path p : chosen) {
            addresses.add(p.address());
            if (p.queue() != null) {
                queueNames.add(p.queue());
            }
        }

        for (String address : addresses) {
            Set<String> routingTypes = new TreeSet<>();
            Set<String> brokerNodes = new TreeSet<>();
            queues.values().stream().filter(q -> q.address.equals(address)).forEach(q -> {
                if (q.routingType != null) {
                    routingTypes.add(q.routingType);
                }
                brokerNodes.addAll(q.brokerNodes);
            });
            nodes.add(new FlowNodeView(
                    addressId(address),
                    NodeKind.ADDRESS,
                    address,
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

        for (String name : queueNames) {
            QueueAgg q = queues.get(name);
            List<Fault> faults = q.noConsumer() ? List.of(Fault.NO_CONSUMER) : List.of();
            nodes.add(new FlowNodeView(
                    queueId(name),
                    NodeKind.QUEUE,
                    name,
                    null,
                    q.messageCount,
                    q.consumerCount,
                    q.routingType == null ? List.of() : List.of(q.routingType),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.copyOf(q.brokerNodes),
                    faults));
            SubjectRate rate = added.get(name);
            edges.add(new FlowEdgeView(
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
                    List.of()));
        }

        drawClients(producers, addresses, NodeKind.PRODUCER, fresh, nodes, edges);
        drawClients(consumers, queueNames, NodeKind.CONSUMER, fresh, nodes, edges);
    }

    private static void drawClients(
            Map<String, ClientAgg> clients,
            Set<String> shownTargets,
            NodeKind kind,
            Freshness fresh,
            List<FlowNodeView> nodes,
            List<FlowEdgeView> edges) {
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
            nodes.add(new FlowNodeView(
                    id,
                    kind,
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
                edges.add(new FlowEdgeView(
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
                        agg.stalled ? List.of(Fault.STALLED) : List.of()));
            }
        }
    }

    private static FlowKpis kpis(
            Map<String, QueueAgg> queues,
            Map<String, ClientAgg> producers,
            Map<String, ClientAgg> consumers,
            Map<String, SubjectRate> added,
            Map<String, SubjectRate> acked,
            List<FlowBrokerNodeView> brokerNodes) {
        Double in = null;
        Double out = null;
        long backlog = 0;
        int faults = 0;
        for (QueueAgg q : queues.values()) {
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
                        "The broker refused to list producers or consumers for Studio's user, so this node's clients are not shown.";
                    case COUNTER_UNAVAILABLE ->
                        "This broker does not report the counters a rate needs; its rates are unavailable, not zero.";
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

    private static Delivery delivery(String routingType) {
        if ("MULTICAST".equalsIgnoreCase(routingType)) {
            return Delivery.COPY;
        }
        return "ANYCAST".equalsIgnoreCase(routingType) ? Delivery.SHARED : null;
    }

    private static Double rate(SubjectRate r) {
        return r == null ? null : r.rate();
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

    private record Freshness(Instant now, Duration sampleInterval, Duration tierC) {
        boolean olderThan(Instant asOf, Duration interval) {
            return Duration.between(asOf, now).compareTo(interval.multipliedBy(3)) > 0;
        }
    }

    private static final class QueueAgg {
        final String name;
        final String address;
        String routingType;
        Long messageCount;
        Long consumerCount;
        final Set<String> brokerNodes = new TreeSet<>();

        QueueAgg(String name, String address) {
            this.name = name;
            this.address = address == null ? "" : address;
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
            return messageCount != null && messageCount > 0 && consumerCount != null && consumerCount == 0;
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

        void add(StoredEdge stored, String nodeName) {
            Edge e = stored.edge();
            members += e.memberCount();
            addIfPresent(protocols, e.protocol());
            addIfPresent(hosts, e.remoteHost());
            addIfPresent(users, e.user());
            addIfPresent(brokerNodes, nodeName);
            TargetAgg t =
                    targets.computeIfAbsent(e.kind() == Kind.PRODUCE ? e.address() : e.queue(), k -> new TargetAgg());
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
}
