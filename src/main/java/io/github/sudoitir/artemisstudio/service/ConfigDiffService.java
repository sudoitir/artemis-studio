package io.github.sudoitir.artemisstudio.service;

import io.github.sudoitir.artemisstudio.broker.BrokerConnectionException;
import io.github.sudoitir.artemisstudio.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.broker.ConfigReader;
import io.github.sudoitir.artemisstudio.broker.ConfigReader.NodeConfig;
import io.github.sudoitir.artemisstudio.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.domain.config.ConfigDiff;
import io.github.sudoitir.artemisstudio.domain.config.ConfigDiff.Entry;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeRepository;
import io.github.sudoitir.artemisstudio.persist.QueueSnapshotEntity;
import io.github.sudoitir.artemisstudio.persist.QueueSnapshotRepository;
import io.github.sudoitir.artemisstudio.scheduler.NodeCallLimiter;
import io.github.sudoitir.artemisstudio.security.Permissions;
import io.github.sudoitir.artemisstudio.web.dto.ConfigViews.ConfigDiffView;
import io.github.sudoitir.artemisstudio.web.dto.ConfigViews.ConfigEntryView;
import io.github.sudoitir.artemisstudio.web.dto.ConfigViews.ConfigSectionView;
import io.github.sudoitir.artemisstudio.web.dto.ConfigViews.ConfigSideView;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Compares two nodes' broker configuration (ADR-0043). Config drift between a
 * primary and its backup — a different {@code journal-directory}, a missing
 * {@code security-setting}, a {@code max-size-bytes} only one side enforces — is
 * silent until failover, when it is expensive.
 *
 * <p>Read-only: no audit event, matching the rule that only mutating calls audit.
 * Each side costs exactly one batched Jolokia POST taken through the per-node rate
 * limiter (non-negotiable #1), following {@code DlqService}.
 *
 * <p>When either side cannot be read, this returns the view with that side marked
 * unavailable and reports <b>no per-key drift at all</b> — the same ethos as
 * {@code DlqService}'s {@code settingsAvailable = false}. A half-diff is worse than
 * no diff: every key the unreachable node did not answer for would read as a
 * removal, which is a catastrophic-looking report of a connection problem.
 */
@Service
@RequiredArgsConstructor
public class ConfigDiffService {

    private final BrokerNodeRepository brokerNodes;
    private final QueueSnapshotRepository queueSnapshots;
    private final BrokerConnections connections;
    private final ConfigReader reader;
    private final NodeCallLimiter limiter;
    private final ClusterAccessGuard clusterAccess;

    @Transactional(readOnly = true)
    public ConfigDiffView compare(UUID clusterId, UUID leftId, UUID rightId) {
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_READ);
        List<BrokerNodeEntity> nodes = brokerNodes.findByClusterIdOrderByNameAsc(clusterId);

        BrokerNodeEntity left = resolveLeft(nodes, leftId, rightId);
        BrokerNodeEntity right = resolveRight(nodes, left, rightId);

        List<String> matches = ConfigReader.matchesFor(addressesOf(clusterId));

        Read leftRead = read(clusterId, left, matches);
        Read rightRead = read(clusterId, right, matches);

        ConfigSideView leftSide = side(left, leftRead, false);

        if (leftRead.config() == null || rightRead.config() == null) {
            String note = "Only one side answered, so no comparison is shown — every key the"
                    + " unreachable node did not answer for would read as a removal.";
            return new ConfigDiffView(
                    clusterId, leftSide, side(right, rightRead, false), false, List.of(), 0, 0, matches.size(), note);
        }

        // A node that is not serving *may* answer with a reduced surface. On Artemis
        // 2.44 it does not (surface check §14, Q1) — a passive backup exposes the same
        // 90 attributes. This guard is for the broker that behaves otherwise: say so
        // rather than report its unexposed attributes as missing configuration.
        boolean reduced = isReducedSurface(leftRead.config(), rightRead.config());
        if (reduced) {
            String note = "The backup is passive and its management surface exposes fewer attributes"
                    + " than the primary's, so a key-by-key comparison would report its unexposed"
                    + " attributes as missing configuration. Compare again after a failover, or"
                    + " against another active node.";
            return new ConfigDiffView(
                    clusterId,
                    side(left, leftRead, false),
                    side(right, rightRead, true),
                    false,
                    List.of(),
                    0,
                    leftRead.config().matchesCompared(),
                    matches.size(),
                    note);
        }

        List<ConfigSectionView> sections = new ArrayList<>();
        sections.add(section(
                ConfigDiff.SECTION_BROKER,
                ConfigDiff.flatten(leftRead.config().brokerAttributes()),
                ConfigDiff.flatten(rightRead.config().brokerAttributes()),
                left,
                right));
        sections.add(section(
                ConfigDiff.SECTION_ADDRESS_SETTINGS,
                ConfigDiff.flattenKeyed(leftRead.config().addressSettings(), "match"),
                ConfigDiff.flattenKeyed(rightRead.config().addressSettings(), "match"),
                left,
                right));
        sections.add(section(
                ConfigDiff.SECTION_SECURITY_SETTINGS,
                ConfigDiff.flattenKeyed(leftRead.config().securitySettings(), "name"),
                ConfigDiff.flattenKeyed(rightRead.config().securitySettings(), "name"),
                left,
                right));
        sections.add(section(
                ConfigDiff.SECTION_ACCEPTORS,
                ConfigDiff.flattenKeyed(leftRead.config().acceptors(), "name"),
                ConfigDiff.flattenKeyed(rightRead.config().acceptors(), "name"),
                left,
                right));

        int drift = sections.stream().mapToInt(ConfigSectionView::driftCount).sum();
        int compared = leftRead.config().matchesCompared();
        String note = compared < matches.size()
                ? "Compared " + compared + " of " + matches.size() + " address settings (the default match"
                        + " \"#\" is always included)."
                : null;

        return new ConfigDiffView(
                clusterId,
                leftSide,
                side(right, rightRead, false),
                true,
                List.copyOf(sections),
                drift,
                compared,
                matches.size(),
                note);
    }

    /** With {@code left} omitted, default to the pair — where drift actually hurts. */
    private BrokerNodeEntity resolveLeft(List<BrokerNodeEntity> nodes, UUID leftId, UUID rightId) {
        if (leftId != null) {
            return require(nodes, leftId);
        }
        if (rightId != null) {
            BrokerNodeEntity right = require(nodes, rightId);
            return partnerOf(nodes, right)
                    .orElseThrow(() -> new BrokerConnectionException(
                            BrokerConnectionException.Kind.UNREACHABLE,
                            "That node has no partner endpoint to compare against. Name both nodes."));
        }
        return nodes.stream()
                .filter(n -> n.getJolokiaUrl() != null)
                .findFirst()
                .orElseThrow(() -> new BrokerConnectionException(
                        BrokerConnectionException.Kind.UNREACHABLE,
                        "This cluster has no node with a management URL yet."));
    }

    private BrokerNodeEntity resolveRight(List<BrokerNodeEntity> nodes, BrokerNodeEntity left, UUID rightId) {
        if (rightId != null && !rightId.equals(left.getId())) {
            return require(nodes, rightId);
        }
        return partnerOf(nodes, left)
                .orElseThrow(() -> new BrokerConnectionException(
                        BrokerConnectionException.Kind.UNREACHABLE,
                        "That node has no partner endpoint to compare against. Name both nodes."));
    }

    /** The other endpoint of the same logical node — the HA pair. */
    private java.util.Optional<BrokerNodeEntity> partnerOf(List<BrokerNodeEntity> nodes, BrokerNodeEntity node) {
        return nodes.stream()
                .filter(n -> !n.getId().equals(node.getId()))
                .filter(n ->
                        n.getArtemisNodeId() != null && Objects.equals(n.getArtemisNodeId(), node.getArtemisNodeId()))
                .findFirst();
    }

    private BrokerNodeEntity require(List<BrokerNodeEntity> nodes, UUID nodeId) {
        return nodes.stream()
                .filter(n -> n.getId().equals(nodeId))
                .findFirst()
                .orElseThrow(() -> new BrokerConnectionException(
                        BrokerConnectionException.Kind.UNREACHABLE, "No such node in this cluster: " + nodeId));
    }

    private Set<String> addressesOf(UUID clusterId) {
        Set<String> addresses = new LinkedHashSet<>();
        for (QueueSnapshotEntity row : queueSnapshots.findByClusterId(clusterId)) {
            addresses.add(row.getAddress());
        }
        return addresses;
    }

    private record Read(NodeConfig config, String failure) {}

    private Read read(UUID clusterId, BrokerNodeEntity node, List<String> matches) {
        if (node.getJolokiaUrl() == null) {
            return new Read(null, "This node has no management URL, so its configuration cannot be read.");
        }
        try {
            acquire(node.getId());
            JolokiaBrokerClient client = connections.forCluster(clusterId, node.getJolokiaUrl());
            return new Read(reader.read(client, matches), null);
        } catch (BrokerConnectionException e) {
            return new Read(null, e.kind() + ": " + e.getMessage());
        }
    }

    private ConfigSideView side(BrokerNodeEntity node, Read read, boolean reducedSurface) {
        NodeConfig config = read.config();
        return new ConfigSideView(
                node.getId(),
                node.getName(),
                config != null,
                config != null && config.active(),
                reducedSurface,
                read.failure());
    }

    /**
     * A passive node whose management surface is genuinely smaller than the serving
     * node's — it does not register some attributes at all.
     *
     * <p>Compares <b>attribute names</b>, deliberately not flattened pointers. A
     * passive backup reports {@code AddressNames: []} where the primary reports ten
     * entries, so a pointer comparison sees ten missing keys and calls a perfectly
     * normal backup "reduced". That is exactly the false positive this whole feature
     * exists to avoid, and the live check against the dev pair caught it.
     */
    private boolean isReducedSurface(NodeConfig left, NodeConfig right) {
        NodeConfig passive = !right.active() ? right : (!left.active() ? left : null);
        if (passive == null) {
            return false;
        }
        NodeConfig serving = passive == right ? left : right;
        if (!serving.active()) {
            return false;
        }
        return !attributeNames(passive).containsAll(attributeNames(serving));
    }

    private Set<String> attributeNames(NodeConfig config) {
        JsonNode attributes = config.brokerAttributes();
        if (attributes == null || !attributes.isObject()) {
            return Set.of();
        }
        Set<String> names = new LinkedHashSet<>();
        attributes.propertyNames().forEach(names::add);
        return names;
    }

    private ConfigSectionView section(
            String section,
            Map<String, String> left,
            Map<String, String> right,
            BrokerNodeEntity leftNode,
            BrokerNodeEntity rightNode) {
        List<Entry> entries = ConfigDiff.compare(section, left, right);
        List<ConfigEntryView> views = entries.stream()
                .map(e -> new ConfigEntryView(
                        e.key(),
                        e.left(),
                        e.right(),
                        e.status().name(),
                        ConfigDiff.statusWord(e.status(), leftNode.getName(), rightNode.getName()),
                        e.classification().name(),
                        e.isDrift()))
                .toList();
        int drift = (int) entries.stream().filter(Entry::isDrift).count();
        return new ConfigSectionView(section, ConfigDiff.sectionLabel(section), views, drift);
    }

    private void acquire(UUID nodeId) {
        try {
            limiter.acquire(nodeId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrokerConnectionException(
                    BrokerConnectionException.Kind.UNREACHABLE, "Interrupted while waiting for the node rate limiter.");
        }
    }
}
