package io.github.sudoitir.artemisstudio.platform.clusters;

import static io.github.sudoitir.artemisstudio.platform.broker.JolokiaJson.bool;
import static io.github.sudoitir.artemisstudio.platform.broker.JolokiaJson.boxedBool;
import static io.github.sudoitir.artemisstudio.platform.broker.JolokiaJson.text;

import io.github.sudoitir.artemisstudio.platform.broker.BrokerConnectionException;
import io.github.sudoitir.artemisstudio.platform.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.platform.broker.JolokiaResponse;
import io.github.sudoitir.artemisstudio.platform.broker.NodeEndpoint;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.BrokerNodeEntity.HaObservation;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.BrokerNodeRepository;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
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
        List<SeedReading> readings = readAnswering(seeds);

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
        return evaluated(clusterId);
    }

    /** The persisted topology, evaluated — no broker calls. */
    @Transactional(readOnly = true)
    public ClusterTopology currentTopology(UUID clusterId) {
        return evaluated(clusterId);
    }

    private ClusterTopology evaluated(UUID clusterId) {
        List<BrokerNodeEntity> rows = nodes.findByClusterIdOrderByNameAsc(clusterId);
        return new ClusterTopology(
                clusterId, evaluator.toLogicalNodes(nodeMapper.toEndpoints(rows), SplitBrainStatus.byNodeId(rows)));
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
                            UUID.randomUUID(),
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
        // Nothing is persisted for a preview, so there is no real cluster id yet; a
        // fresh one only gives every rendered node a stable React key (ADR-0014's
        // domain records are not otherwise nullable-aware).
        return new ClusterTopology(UUID.randomUUID(), scratch.toLogicalNodes(List.copyOf(byName.values())));
    }

    private static NodeEndpoint previewEndpoint(String name, String haRole, String nodeId) {
        return new NodeEndpoint(
                UUID.randomUUID(),
                name,
                nodeId,
                null,
                name,
                haRole,
                "UNKNOWN",
                false,
                null,
                null,
                null,
                null,
                null,
                true,
                false,
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

    /**
     * A seed's management URL is its identity: the row already holding it, else a
     * connector-named row without a URL on the seed's host (or, when the connector
     * is named differently from the management address, with the seed's NodeID and
     * role), else a new row. A URL is never moved from one row to another. HA role
     * and state are observed and are written to whichever row that is.
     */
    private void attachSeed(UUID clusterId, SeedReading r) {
        String haRole = evaluator.deriveHaRole(r.backup(), r.clustered());
        String state = evaluator.deriveState(r.started());

        List<BrokerNodeEntity> rows = nodes.findByClusterIdOrderByNameAsc(clusterId);
        BrokerNodeEntity node = rows.stream()
                .filter(n -> r.jolokiaUrl().equals(n.getJolokiaUrl()))
                .findFirst()
                .or(() -> unmanaged(rows, n -> connectorHost(n.getName()).equals(seedHost(r.jolokiaUrl()))))
                .or(() -> unmanaged(
                        rows,
                        n -> r.nodeId() != null
                                && r.nodeId().equals(n.getArtemisNodeId())
                                && haRole.equals(n.getHaRole())))
                .orElseGet(() ->
                        nodes.save(BrokerNodeEntity.fromSeed(clusterId, seedName(r.jolokiaUrl()), haRole, r.nodeId())));

        node.attachManagementUrl(r.jolokiaUrl());
        node.applyHaState(
                new HaObservation(r.active(), state, haRole, r.replicaSync(), r.version(), r.nodeId()),
                0L,
                Instant.now());
        nodes.save(node);
    }

    /** The first row with no management URL, not under a manual override, that the filter accepts. */
    private static Optional<BrokerNodeEntity> unmanaged(
            List<BrokerNodeEntity> rows, Predicate<BrokerNodeEntity> match) {
        return rows.stream()
                .filter(n -> n.getJolokiaUrl() == null && !n.isManualOverride())
                .filter(match)
                .findFirst();
    }

    /**
     * Reads each seed on its own: one that does not answer is skipped (the scrape's
     * tier A records its error) and discovery goes on with the others. Only when none
     * answered is there nothing to discover from, and the first failure is thrown.
     */
    private static List<SeedReading> readAnswering(List<ProbedSeed> seeds) {
        List<SeedReading> readings = new ArrayList<>();
        BrokerConnectionException firstFailure = null;
        for (ProbedSeed seed : seeds) {
            try {
                readings.add(read(seed));
            } catch (BrokerConnectionException e) {
                if (firstFailure == null) {
                    firstFailure = e;
                }
            }
        }
        if (readings.isEmpty() && firstFailure != null) {
            throw firstFailure;
        }
        return readings;
    }

    private static SeedReading read(ProbedSeed seed) {
        JolokiaResponse response = seed.client().readBrokerAttributes(HA_ATTRS);
        if (!response.ok()) {
            throw new BrokerConnectionException(
                    BrokerConnectionException.Kind.BAD_RESPONSE, "HA read failed: " + response.failure());
        }
        JsonNode ha = response.value();
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

    private static String seedHost(String jolokiaUrl) {
        try {
            return URI.create(jolokiaUrl).getHost();
        } catch (RuntimeException _) {
            return null;
        }
    }

    /** The host of a {@code host:port} connector name. */
    private static String connectorHost(String connector) {
        int colon = connector.lastIndexOf(':');
        return colon > 0 ? connector.substring(0, colon) : connector;
    }

    private static String seedName(String jolokiaUrl) {
        try {
            URI u = URI.create(jolokiaUrl);
            int port = u.getPort();
            return port > 0 ? u.getHost() + ":" + port : u.getHost();
        } catch (RuntimeException _) {
            return jolokiaUrl;
        }
    }
}
