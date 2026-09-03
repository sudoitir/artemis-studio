package io.github.sudoitir.artemisstudio.domain.topology;

import static io.github.sudoitir.artemisstudio.broker.JolokiaJson.bool;
import static io.github.sudoitir.artemisstudio.broker.JolokiaJson.boxedBool;
import static io.github.sudoitir.artemisstudio.broker.JolokiaJson.text;

import io.github.sudoitir.artemisstudio.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.mapper.BrokerNodeMapper;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeRepository;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Turns one or more reachable seed connections into persisted {@code broker_node}
 * rows and a {@link ClusterTopology}.
 *
 * <p>Discovery keys on the broker-reported NodeID: a primary and its synced
 * backup share it (Phase 0), so they merge into one {@link LogicalNode}.
 * {@code listNetworkTopology()} returns broker-to-broker {@code host:port}
 * connectors, never Jolokia URLs, so a discovered endpoint lands with
 * {@code coreUrl} set and {@code jolokiaUrl} null — known, but not yet manageable
 * until an operator supplies a management URL. A row under a manual override is
 * never rewritten by discovery. A topology view that omits the {@code backup}
 * key (the post-failover shape) means "not currently announced", not "delete".
 */
@Component
@RequiredArgsConstructor
public class TopologyDiscovery {

    private static final String[] HA_ATTRS = {
        "Active", "Started", "Backup", "ReplicaSync", "NodeID", "Clustered", "Version"
    };

    private final BrokerNodeRepository nodes;
    private final HaStateEvaluator evaluator;
    private final BrokerNodeMapper nodeMapper;
    private final SplitBrainRegistry splitBrainRegistry;

    /** A seed the caller has already connected to. */
    public record ProbedSeed(String jolokiaUrl, JolokiaBrokerClient client) {}

    private record SeedReading(
            String jolokiaUrl,
            String nodeId,
            boolean backup,
            Boolean started,
            boolean active,
            Boolean replicaSync,
            String version,
            Boolean clustered,
            List<TopologyEntry> entries) {}

    private record TopologyEntry(String nodeId, String live, String primary, String backup) {
        String primaryConnector() {
            return primary != null ? primary : live;
        }
    }

    @Transactional
    public ClusterTopology discover(UUID clusterId, List<ProbedSeed> seeds) {
        List<SeedReading> readings = seeds.stream().map(TopologyDiscovery::read).toList();

        // 1. Connector-named discovered rows from every seed's topology view.
        for (SeedReading r : readings) {
            for (TopologyEntry e : r.entries()) {
                upsertDiscovered(clusterId, e.primaryConnector(), "PRIMARY", e.nodeId());
                if (e.backup() != null) {
                    upsertDiscovered(clusterId, e.backup(), "BACKUP", e.nodeId());
                }
            }
        }

        // 2. Attach each seed's management URL and live state to its own row.
        for (SeedReading r : readings) {
            attachSeed(clusterId, r);
        }

        // 3. Re-read and evaluate.
        return currentTopology(clusterId);
    }

    /** The persisted topology, evaluated — no broker calls. */
    @Transactional(readOnly = true)
    public ClusterTopology currentTopology(UUID clusterId) {
        List<NodeEndpoint> endpoints = nodeMapper.toEndpoints(nodes.findByClusterIdOrderByNameAsc(clusterId));
        return new ClusterTopology(
                clusterId, evaluator.toLogicalNodes(endpoints, splitBrainRegistry.statusesFor(clusterId)));
    }

    /**
     * A non-persisting view of what the seeds report — for {@code ?dryRun=true}.
     * Reads the brokers, builds the topology entirely in memory, writes nothing,
     * and uses a throwaway evaluator so the real split-brain ratchet is untouched.
     */
    public ClusterTopology preview(List<ProbedSeed> seeds) {
        List<SeedReading> readings = seeds.stream().map(TopologyDiscovery::read).toList();
        HaStateEvaluator scratch = new HaStateEvaluator();
        Map<String, NodeEndpoint> byName = new LinkedHashMap<>();

        for (SeedReading r : readings) {
            for (TopologyEntry e : r.entries()) {
                byName.computeIfAbsent(e.primaryConnector(), k -> previewEndpoint(k, "PRIMARY", e.nodeId()));
                if (e.backup() != null) {
                    byName.computeIfAbsent(e.backup(), k -> previewEndpoint(k, "BACKUP", e.nodeId()));
                }
            }
        }
        for (SeedReading r : readings) {
            String haRole = scratch.deriveHaRole(r.backup(), r.clustered());
            String key = byName.entrySet().stream()
                    .filter(en -> r.nodeId() != null
                            && r.nodeId().equals(en.getValue().artemisNodeId()))
                    .filter(en -> haRole.equals(en.getValue().haRole()))
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElse(seedName(r.jolokiaUrl()));
            byName.put(
                    key,
                    new NodeEndpoint(
                            null,
                            key,
                            r.nodeId(),
                            r.jolokiaUrl(),
                            null,
                            haRole,
                            scratch.deriveState(r.started()),
                            r.active(),
                            r.replicaSync(),
                            null,
                            r.version(),
                            null,
                            Instant.now(),
                            false,
                            false,
                            true));
        }
        return new ClusterTopology(null, scratch.toLogicalNodes(List.copyOf(byName.values())));
    }

    private static NodeEndpoint previewEndpoint(String name, String haRole, String nodeId) {
        return new NodeEndpoint(
                null, name, nodeId, null, name, haRole, "UNKNOWN", false, null, null, null, null, null, true, false,
                false);
    }

    private void upsertDiscovered(UUID clusterId, String connector, String haRole, String nodeId) {
        Optional<BrokerNodeEntity> existing = nodes.findByClusterIdAndName(clusterId, connector);
        if (existing.isPresent()) {
            existing.get().mergeDiscovered(connector, haRole, nodeId);
            nodes.save(existing.get());
            return;
        }
        nodes.save(BrokerNodeEntity.discovered(clusterId, connector, haRole, nodeId));
    }

    private void attachSeed(UUID clusterId, SeedReading r) {
        String haRole = evaluator.deriveHaRole(r.backup(), r.clustered());
        String state = evaluator.deriveState(r.started());

        BrokerNodeEntity node = nodes.findByClusterIdOrderByNameAsc(clusterId).stream()
                .filter(n -> r.nodeId() != null && r.nodeId().equals(n.getArtemisNodeId()))
                .filter(n -> haRole.equals(n.getHaRole()))
                .findFirst()
                .orElseGet(() ->
                        nodes.save(BrokerNodeEntity.fromSeed(clusterId, seedName(r.jolokiaUrl()), haRole, r.nodeId())));

        node.attachManagementUrl(r.jolokiaUrl());
        node.applyHaState(r.active(), state, haRole, r.replicaSync(), 0L, r.version(), r.nodeId(), Instant.now());
        nodes.save(node);
    }

    private static SeedReading read(ProbedSeed seed) {
        JsonNode ha = seed.client().readBrokerAttributes(HA_ATTRS).value();
        JsonNode topology = seed.client().execOnBrokerParsed("listNetworkTopology()");

        List<TopologyEntry> entries = new ArrayList<>();
        if (topology != null && topology.isArray()) {
            for (JsonNode e : topology) {
                entries.add(
                        new TopologyEntry(text(e, "nodeID"), text(e, "live"), text(e, "primary"), text(e, "backup")));
            }
        }
        return new SeedReading(
                seed.jolokiaUrl(),
                text(ha, "NodeID"),
                bool(ha, "Backup"),
                boxedBool(ha, "Started"),
                bool(ha, "Active"),
                boxedBool(ha, "ReplicaSync"),
                text(ha, "Version"),
                boxedBool(ha, "Clustered"),
                entries);
    }

    private static String seedName(String jolokiaUrl) {
        try {
            URI u = URI.create(jolokiaUrl);
            int port = u.getPort();
            return port > 0 ? u.getHost() + ":" + port : u.getHost();
        } catch (RuntimeException e) {
            return jolokiaUrl;
        }
    }
}
