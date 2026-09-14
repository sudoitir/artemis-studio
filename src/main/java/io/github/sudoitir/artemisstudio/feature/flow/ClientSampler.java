package io.github.sudoitir.artemisstudio.feature.flow;

import static io.github.sudoitir.artemisstudio.platform.broker.BrokerListOps.num;
import static io.github.sudoitir.artemisstudio.platform.broker.BrokerListOps.str;

import io.github.sudoitir.artemisstudio.feature.flow.ClientEdges.Edge;
import io.github.sudoitir.artemisstudio.feature.flow.ClientEdges.Kind;
import io.github.sudoitir.artemisstudio.feature.flow.ClientEdges.Member;
import io.github.sudoitir.artemisstudio.feature.flow.FlowStore.NodeSample;
import io.github.sudoitir.artemisstudio.feature.flow.MemberDeltas.MemberRate;
import io.github.sudoitir.artemisstudio.feature.flow.MemberDeltas.Reading;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsService;
import io.github.sudoitir.artemisstudio.kernel.stream.SseHub;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerConnectionException;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.platform.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.platform.broker.JolokiaRequest;
import io.github.sudoitir.artemisstudio.platform.broker.JolokiaResponse;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterDirectory;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterLock;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterNode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Samples producers and consumers of the clusters whose flow is observed (ADR-0081).
 *
 * <p>Per observed cluster: take the {@code FLOW_SAMPLE} lock or skip; per serving node, one bulk
 * POST carrying {@code listProducers} and {@code listConsumers}, capped at the per-node row
 * setting; per-member deltas; aggregation into client edges; one short transaction per node after
 * the broker calls; a {@code flow} signal only when what was persisted changed.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ClientSampler {

    static final String LIST_PRODUCERS = "listProducers(java.lang.String,int,int)";
    static final String LIST_CONSUMERS = "listConsumers(java.lang.String,int,int)";

    private final FlowStore store;
    private final ClusterDirectory directory;
    private final BrokerConnections connections;
    private final ClusterLock lock;
    private final SettingsService settings;
    private final SseHub hub;
    private final MeterRegistry meters;
    private final Clock clock;

    /** Per cluster, per node and kind: the previous sweep's counters. Held only while this instance samples. */
    private final Map<UUID, Map<String, MemberDeltas>> deltas = new ConcurrentHashMap<>();

    /** Per cluster: a digest of what the last sweep persisted, so an unchanged sweep sends no signal. */
    private final Map<UUID, Integer> digests = new ConcurrentHashMap<>();

    private final Set<UUID> running = ConcurrentHashMap.newKeySet();

    /** One pass over every observed cluster. Clusters run concurrently; a slow one delays no other. */
    public void sweepObserved() {
        Instant now = clock.instant();
        store.forgetUnobserved(now.minus(FlowSettings.sampleInterval(settings).multipliedBy(3)));
        Set<UUID> observed = store.observedClusters(now);
        deltas.keySet().retainAll(observed);
        digests.keySet().retainAll(observed);
        if (observed.isEmpty()) {
            skipped("no-lease");
            return;
        }
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            observed.forEach(clusterId -> pool.submit(() -> sweepIfHeld(clusterId)));
        }
    }

    private void sweepIfHeld(UUID clusterId) {
        if (!running.add(clusterId)) {
            skipped("overlap");
            return;
        }
        try {
            if (!lock.runIfHeld(clusterId, ClusterLock.Scope.FLOW_SAMPLE, () -> sweep(clusterId))) {
                // Another instance samples this cluster. If this one takes over later, its first
                // sweep must read "measuring" rather than divide by a stale baseline.
                deltas.remove(clusterId);
                digests.remove(clusterId);
                skipped("lock-held");
            }
        } catch (RuntimeException e) {
            log.warn("Flow sweep of cluster {} failed: {}", clusterId, e.getMessage());
        } finally {
            running.remove(clusterId);
        }
    }

    void sweep(UUID clusterId) {
        Timer.Sample timing = Timer.start(meters);
        int cap = Math.max(1, settings.intValue(FlowSettings.MAX_ROWS_PER_NODE));
        List<ClusterNode> nodes = servingManageableNodes(clusterId);
        Map<ClusterNode, Future<NodeResult>> pending = new LinkedHashMap<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (ClusterNode node : nodes) {
                pending.put(node, pool.submit(() -> sampleNode(clusterId, node, cap)));
            }
        }
        Map<String, MemberDeltas> clusterDeltas = deltas.computeIfAbsent(clusterId, k -> new ConcurrentHashMap<>());
        List<Object> digest = new ArrayList<>();
        for (var entry : pending.entrySet()) {
            ClusterNode node = entry.getKey();
            NodeResult result = entry.getValue().resultNow();
            List<Edge> edges = result.sample().errorKind() == null || result.partial()
                    ? edges(clusterDeltas, node.getId(), result)
                    : List.of();
            store.persistNode(result.sample(), edges);
            meters.counter("studio.flow.sample.rows").increment(result.members().size());
            if (result.truncated()) {
                meters.counter("studio.flow.sample.truncated").increment();
            }
            digest.add(result.sample().errorKind());
            edges.stream()
                    .sorted(Comparator.comparing(Edge::toString))
                    .forEach(e -> digest.add(List.of(
                            e.kind(),
                            e.clientId(),
                            e.user(),
                            e.remoteHost(),
                            e.address(),
                            e.queue(),
                            e.memberCount(),
                            e.rate() == null ? -1L : Math.round(e.rate() * 10),
                            e.stalled())));
        }
        timing.stop(meters.timer("studio.flow.sample.duration"));
        Integer previous = digests.put(clusterId, digest.hashCode());
        if (!Objects.equals(previous, digest.hashCode())) {
            hub.publish(clusterId, FlowModule.TOPIC);
        }
        log.debug("Flow sweep of cluster {}: {} node(s)", clusterId, nodes.size());
    }

    /** Both listings from one node in one POST. Broker I/O only; nothing here touches the database. */
    NodeResult sampleNode(UUID clusterId, ClusterNode node, int cap) {
        Instant at = clock.instant();
        try {
            JolokiaBrokerClient client = connections.forCluster(clusterId, node.getJolokiaUrl());
            String mbean = client.resolveBrokerObjectName();
            List<JolokiaResponse> entries = client.batch(List.of(
                    JolokiaRequest.exec(mbean, LIST_PRODUCERS, "", 1, cap),
                    JolokiaRequest.exec(mbean, LIST_CONSUMERS, "", 1, cap)));
            if (entries.size() != 2) {
                return failed(clusterId, node, at, "BAD_RESPONSE", "The broker returned an incomplete response.");
            }
            Listing producers = listing(client, entries.get(0));
            Listing consumers = listing(client, entries.get(1));
            List<Member> members = new ArrayList<>();
            List<Reading> producerReadings = new ArrayList<>();
            List<Reading> consumerReadings = new ArrayList<>();
            boolean counterMissing = false;
            for (JsonNode row : producers.rows()) {
                counterMissing |= !row.has("msgSent");
                producerReadings.add(new Reading(memberId(row), num(row, "msgSent"), 0));
                members.add(member(Kind.PRODUCE, row, str(row, "address"), null));
            }
            for (JsonNode row : consumers.rows()) {
                counterMissing |= !row.has("messagesAcknowledged");
                consumerReadings.add(
                        new Reading(memberId(row), num(row, "messagesAcknowledged"), num(row, "messagesInTransit")));
                members.add(member(Kind.CONSUME, row, str(row, "address"), str(row, "queue")));
            }
            String errorKind = producers.error() != null
                    ? producers.errorKind()
                    : consumers.error() != null ? consumers.errorKind() : counterMissing ? "COUNTER_UNAVAILABLE" : null;
            String error = producers.error() != null ? producers.error() : consumers.error();
            NodeSample sample = new NodeSample(
                    node.getId(),
                    clusterId,
                    at,
                    producers.rows().size(),
                    (int) Math.min(Integer.MAX_VALUE, producers.total()),
                    consumers.rows().size(),
                    (int) Math.min(Integer.MAX_VALUE, consumers.total()),
                    error,
                    errorKind);
            boolean partial = errorKind == null
                    || "COUNTER_UNAVAILABLE".equals(errorKind)
                    || !producers.rows().isEmpty()
                    || !consumers.rows().isEmpty();
            boolean truncated = producers.total() > producers.rows().size()
                    || consumers.total() > consumers.rows().size();
            return new NodeResult(sample, members, producerReadings, consumerReadings, partial, truncated);
        } catch (BrokerConnectionException e) {
            return failed(clusterId, node, at, e.kind().name(), e.getMessage());
        } catch (RuntimeException e) {
            return failed(clusterId, node, at, "BAD_RESPONSE", e.getMessage());
        }
    }

    private List<Edge> edges(Map<String, MemberDeltas> clusterDeltas, UUID nodeId, NodeResult result) {
        Instant at = result.sample().sampledAt();
        Map<String, MemberRate> producerRates = clusterDeltas
                .computeIfAbsent(nodeId + "/P", k -> new MemberDeltas())
                .apply(at, result.producerReadings());
        Map<String, MemberRate> consumerRates = clusterDeltas
                .computeIfAbsent(nodeId + "/C", k -> new MemberDeltas())
                .apply(at, result.consumerReadings());
        List<Member> rated = new ArrayList<>(result.members().size());
        int p = 0;
        int c = 0;
        for (Member m : result.members()) {
            Reading reading = m.kind() == Kind.PRODUCE
                    ? result.producerReadings().get(p++)
                    : result.consumerReadings().get(c++);
            MemberRate rate = (m.kind() == Kind.PRODUCE ? producerRates : consumerRates).get(reading.memberId());
            rated.add(new Member(
                    m.kind(),
                    m.clientId(),
                    m.user(),
                    m.remoteAddress(),
                    m.protocol(),
                    m.address(),
                    m.queue(),
                    rate == null ? null : rate.rate(),
                    reading.unacked(),
                    rate != null && rate.stalled()));
        }
        return ClientEdges.aggregate(rated);
    }

    private static Member member(Kind kind, JsonNode row, String address, String queue) {
        return new Member(
                kind,
                str(row, "clientID"),
                firstNonBlank(str(row, "validatedUser"), str(row, "user")),
                str(row, "remoteAddress"),
                str(row, "protocol"),
                address,
                queue,
                null,
                0,
                false);
    }

    /** Broker ids are unique per node; the session is added so a reused id in a new session is a new member. */
    private static String memberId(JsonNode row) {
        return str(row, "id") + "@" + str(row, "session");
    }

    private static Listing listing(JolokiaBrokerClient client, JolokiaResponse entry) {
        if (!entry.ok()) {
            String error = entry.error() != null ? entry.error() : "status " + entry.status();
            String lower = error.toLowerCase(java.util.Locale.ROOT);
            String kind = entry.status() == 403 || lower.contains("permission") || lower.contains("security")
                    ? "PERMISSION_DENIED"
                    : "BAD_RESPONSE";
            return new Listing(List.of(), 0, error, kind);
        }
        JsonNode env = client.parsed(entry);
        JsonNode data = env == null ? null : env.get("data");
        List<JsonNode> rows = new ArrayList<>();
        if (data != null && data.isArray()) {
            data.forEach(rows::add);
        }
        long total = env == null ? 0L : env.path("count").asLong(rows.size());
        return new Listing(rows, total, null, null);
    }

    private static NodeResult failed(UUID clusterId, ClusterNode node, Instant at, String kind, String error) {
        return new NodeResult(
                new NodeSample(node.getId(), clusterId, at, 0, 0, 0, 0, error, kind),
                List.of(),
                List.of(),
                List.of(),
                false,
                false);
    }

    /** One manageable endpoint per NodeID: the active one when the pair reports one. */
    private List<ClusterNode> servingManageableNodes(UUID clusterId) {
        Map<String, ClusterNode> perNodeId = new LinkedHashMap<>();
        for (ClusterNode n : directory.nodes(clusterId)) {
            if (n.getJolokiaUrl() == null) {
                continue;
            }
            String key = n.getArtemisNodeId() != null ? n.getArtemisNodeId() : "id:" + n.getId();
            perNodeId.merge(key, n, (kept, candidate) -> Boolean.TRUE.equals(candidate.getActive()) ? candidate : kept);
        }
        return List.copyOf(perNodeId.values());
    }

    private void skipped(String reason) {
        meters.counter("studio.flow.sample.skipped", "reason", reason).increment();
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private record Listing(List<JsonNode> rows, long total, String error, String errorKind) {}

    record NodeResult(
            NodeSample sample,
            List<Member> members,
            List<Reading> producerReadings,
            List<Reading> consumerReadings,
            boolean partial,
            boolean truncated) {}
}
