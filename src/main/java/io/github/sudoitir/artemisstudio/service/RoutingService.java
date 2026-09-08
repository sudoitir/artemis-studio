package io.github.sudoitir.artemisstudio.service;

import io.github.sudoitir.artemisstudio.broker.BrokerConnectionException;
import io.github.sudoitir.artemisstudio.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.broker.routing.BridgeRow;
import io.github.sudoitir.artemisstudio.broker.routing.DivertOperations;
import io.github.sudoitir.artemisstudio.broker.routing.DivertRow;
import io.github.sudoitir.artemisstudio.persist.AuditEventEntity;
import io.github.sudoitir.artemisstudio.persist.AuditEventRepository;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeRepository;
import io.github.sudoitir.artemisstudio.scheduler.NodeCallLimiter;
import io.github.sudoitir.artemisstudio.security.Permissions;
import io.github.sudoitir.artemisstudio.web.dto.ResourceViews.PagedView;
import io.github.sudoitir.artemisstudio.web.dto.RoutingViews.BridgeNodeCell;
import io.github.sudoitir.artemisstudio.web.dto.RoutingViews.BridgeView;
import io.github.sudoitir.artemisstudio.web.dto.RoutingViews.DivertView;
import io.github.sudoitir.artemisstudio.web.dto.RoutingViews.NodeRef;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The cross-node routing views — diverts and bridges — read live through to the
 * brokers, on the same pattern as {@code PagedListService} (ADR-0017): one fan-out
 * per serving node, rows merged, filtered, sorted and paged in memory, a node that
 * errors contributing nothing rather than failing the view.
 *
 * <p>Reading costs two round trips per node rather than one, and cannot cost fewer.
 * A divert is a sub-component of its source address, so its object name carries an
 * address that {@code DivertNames} does not report — the first call is a JMX search
 * and the second a batched read. See {@code DivertOperations}.
 *
 * <p>A divert is merged across nodes by name: one divert deployed on three nodes is
 * one row attributed to three nodes, not three rows. The same divert name with a
 * different source or destination on some node is a real divergence, so the merge
 * key is the whole routing shape and such a divert appears as two rows, each naming
 * the nodes it was found on.
 *
 * <p>The rows carry no claim about where a divert came from. Artemis exposes no
 * marker for it and no configured-state source (ADR-0065 D2). Ownership is a
 * separate, honest fact drawn from Studio's own records.
 */
@Service
@RequiredArgsConstructor
public class RoutingService {

    /**
     * The name prefix Studio reserves for the diverts that serve message capture.
     * A divert under it is Studio's by construction, without consulting anything.
     */
    public static final String CAPTURE_DIVERT_PREFIX = "artemis-studio.capture.";

    /** Ownership Studio can assert. Anything else is left unattributed, on purpose. */
    private static final String OWNER_CAPTURE = "MESSAGE_CAPTURE";

    private static final String OWNER_OPERATOR = "OPERATOR";

    private final BrokerNodeRepository nodes;
    private final BrokerConnections connections;
    private final DivertOperations divertOps;
    private final NodeCallLimiter limiter;
    private final ClusterAccessGuard clusterAccess;
    private final AuditEventRepository auditEvents;

    @Transactional(readOnly = true)
    public PagedView<DivertView> diverts(UUID clusterId, ResourceQuery query) {
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_READ);
        List<BrokerNodeEntity> serving = servingManageableNodes(clusterId);
        int nodesTotal = serving.size();
        Set<String> ownedByOperator = operatorOwnedDivertNames(clusterId);

        List<DivertRow> rows = fanOut(clusterId, serving, divertOps::listDiverts);

        // Merged on the whole routing shape, not on the name: the same name pointing
        // somewhere else on one node is a divergence, and collapsing it would hide
        // exactly the thing an operator opened this view to find.
        Map<List<String>, List<DivertRow>> merged = new LinkedHashMap<>();
        for (DivertRow row : rows) {
            merged.computeIfAbsent(divertKey(row), k -> new ArrayList<>()).add(row);
        }

        List<DivertView> views = merged.values().stream()
                .map(group -> toDivertView(group, nodesTotal, ownedByOperator))
                .toList();

        // Filtering by address is the question "what touches this address", so the
        // filter covers both ends of the divert as well as its name.
        List<DivertView> filtered = views.stream()
                .filter(v ->
                        query.matches(v.name()) || query.matches(v.address()) || query.matches(v.forwardingAddress()))
                .toList();
        return query.paginate(filtered, Comparator.comparing(DivertView::address, nullSafe()));
    }

    @Transactional(readOnly = true)
    public PagedView<BridgeView> bridges(UUID clusterId, ResourceQuery query) {
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_READ);
        List<BrokerNodeEntity> serving = servingManageableNodes(clusterId);
        int nodesTotal = serving.size();

        List<BridgeRow> rows = fanOut(clusterId, serving, divertOps::listBridges);

        Map<String, List<BridgeRow>> merged = new LinkedHashMap<>();
        for (BridgeRow row : rows) {
            merged.computeIfAbsent(String.valueOf(row.name()), k -> new ArrayList<>())
                    .add(row);
        }
        List<BridgeView> views = merged.values().stream()
                .map(group -> toBridgeView(group, nodesTotal))
                .filter(v ->
                        query.matches(v.name()) || query.matches(v.queueName()) || query.matches(v.forwardingAddress()))
                .toList();
        return query.paginate(views, Comparator.comparing(BridgeView::name, nullSafe()));
    }

    // ---- fan-out ---------------------------------------------------------

    /** What one node contributes to a routing view. */
    @FunctionalInterface
    private interface NodeRead<T> {
        List<T> apply(JolokiaBrokerClient client, UUID nodeId, String nodeName);
    }

    private <T> List<T> fanOut(UUID clusterId, List<BrokerNodeEntity> serving, NodeRead<T> read) {
        if (serving.isEmpty()) {
            throw new BrokerConnectionException(
                    BrokerConnectionException.Kind.UNREACHABLE,
                    "This cluster has no reachable node with a management URL.");
        }
        List<T> merged = new ArrayList<>();
        BrokerConnectionException firstError = null;
        for (BrokerNodeEntity node : serving) {
            try {
                limiter.acquire(node.getId());
                JolokiaBrokerClient client = connections.forCluster(clusterId, node.getJolokiaUrl());
                merged.addAll(read.apply(client, node.getId(), node.getName()));
            } catch (BrokerConnectionException e) {
                if (firstError == null) {
                    firstError = e;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BrokerConnectionException(
                        BrokerConnectionException.Kind.UNREACHABLE, "Timed out waiting for a per-node call permit.");
            }
        }
        // A cluster with no diverts at all and a cluster no node answered for are
        // different answers, and only the second is an error.
        if (merged.isEmpty() && firstError != null) {
            throw firstError;
        }
        return merged;
    }

    // ---- merging ---------------------------------------------------------

    private static List<String> divertKey(DivertRow row) {
        return java.util.Arrays.asList(
                row.uniqueName(),
                row.address(),
                row.forwardingAddress(),
                row.filter(),
                row.routingType(),
                String.valueOf(row.exclusive()));
    }

    private DivertView toDivertView(List<DivertRow> group, int nodesTotal, Set<String> ownedByOperator) {
        DivertRow first = group.get(0);
        String name = first.uniqueName();
        boolean capture = name != null && name.startsWith(CAPTURE_DIVERT_PREFIX);
        String owner = capture ? OWNER_CAPTURE : (ownedByOperator.contains(name) ? OWNER_OPERATOR : null);
        return new DivertView(
                name,
                first.routingName(),
                first.address(),
                first.forwardingAddress(),
                first.filter(),
                first.routingType(),
                first.transformerClassName(),
                first.exclusive(),
                first.retroactiveResource(),
                owner,
                capture ? captureSubscriptionId(name) : null,
                group.size(),
                nodesTotal,
                group.stream().map(r -> new NodeRef(r.nodeId(), r.nodeName())).toList());
    }

    private static BridgeView toBridgeView(List<BridgeRow> group, int nodesTotal) {
        BridgeRow first = group.get(0);
        return new BridgeView(
                first.name(),
                first.queueName(),
                first.forwardingAddress(),
                first.filterString(),
                first.discoveryGroupName(),
                first.transformerClassName(),
                first.staticConnectors(),
                group.stream().mapToLong(BridgeRow::messagesAcknowledged).sum(),
                group.stream()
                        .mapToLong(BridgeRow::messagesPendingAcknowledgement)
                        .sum(),
                group.stream().anyMatch(BridgeRow::started),
                group.stream().anyMatch(BridgeRow::connected),
                first.useDuplicateDetection(),
                first.highlyAvailable(),
                group.size(),
                nodesTotal,
                group.stream()
                        .map(r -> new BridgeNodeCell(
                                r.nodeId(),
                                r.nodeName(),
                                r.started(),
                                r.connected(),
                                r.messagesAcknowledged(),
                                r.messagesPendingAcknowledgement()))
                        .toList());
    }

    /**
     * The capture subscription a capture divert serves, from the name Studio itself
     * assigned. The last dot-separated segment is the subscription id; a name that
     * does not parse is reported without one rather than with a wrong one.
     */
    private static UUID captureSubscriptionId(String divertName) {
        int lastDot = divertName.lastIndexOf('.');
        if (lastDot < 0 || lastDot == divertName.length() - 1) {
            return null;
        }
        try {
            return UUID.fromString(divertName.substring(lastDot + 1));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The diverts Studio created through the routing screen and has not since
     * deleted, folded from its own audit trail — the only record that survives,
     * because the broker keeps none (ADR-0065 D2).
     *
     * <p>A create counts whatever its outcome, because a fan-out that failed on one
     * node is recorded as failed and still created the divert everywhere else; only a
     * <em>successful</em> delete takes the name back out. Over-inclusion is harmless
     * here — the set is only ever consulted for divert names that actually exist on a
     * broker — where under-inclusion would leave a divert Studio created unmarked.
     *
     * <p>What the marking claims is therefore exactly what the audit trail supports:
     * "Studio created a divert by this name and has no record of removing it". A
     * divert Studio created, someone deleted out of band, and someone else recreated
     * would be attributed to Studio wrongly; that is the limit of this evidence, and
     * it is a smaller error than claiming an origin the broker does not record at all.
     */
    private Set<String> operatorOwnedDivertNames(UUID clusterId) {
        Set<String> owned = new LinkedHashSet<>();
        List<AuditEventEntity> events =
                auditEvents.findByClusterIdAndTargetTypeAndDryRunFalseOrderByTsAsc(clusterId, "DIVERT");
        for (AuditEventEntity event : events) {
            if (event.getTargetName() == null) {
                continue;
            }
            if (LifecycleKind.CREATE_DIVERT.auditName().equals(event.getAction())) {
                owned.add(event.getTargetName());
            } else if (LifecycleKind.DELETE_DIVERT.auditName().equals(event.getAction())
                    && "SUCCESS".equals(event.getOutcome())) {
                owned.remove(event.getTargetName());
            }
        }
        return owned;
    }

    // ---- plumbing --------------------------------------------------------

    /** One manageable endpoint per NodeID — the active one when the pair reports one. */
    private List<BrokerNodeEntity> servingManageableNodes(UUID clusterId) {
        Map<String, BrokerNodeEntity> perNodeId = new LinkedHashMap<>();
        for (BrokerNodeEntity n : nodes.findByClusterIdOrderByNameAsc(clusterId)) {
            if (n.getJolokiaUrl() == null) {
                continue;
            }
            String key = n.getArtemisNodeId() != null ? n.getArtemisNodeId() : "id:" + n.getId();
            perNodeId.merge(key, n, (kept, candidate) -> Boolean.TRUE.equals(candidate.getActive()) ? candidate : kept);
        }
        return List.copyOf(perNodeId.values());
    }

    private static Comparator<String> nullSafe() {
        return Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER);
    }
}
