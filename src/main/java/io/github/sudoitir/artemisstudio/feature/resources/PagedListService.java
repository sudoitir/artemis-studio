package io.github.sudoitir.artemisstudio.feature.resources;

import io.github.sudoitir.artemisstudio.feature.resources.ResourceViewMapper.NodeRef;
import io.github.sudoitir.artemisstudio.feature.resources.web.ResourceViews.AddressView;
import io.github.sudoitir.artemisstudio.feature.resources.web.ResourceViews.ConnectionView;
import io.github.sudoitir.artemisstudio.feature.resources.web.ResourceViews.ConsumerView;
import io.github.sudoitir.artemisstudio.feature.resources.web.ResourceViews.ProducerView;
import io.github.sudoitir.artemisstudio.feature.resources.web.ResourceViews.SessionView;
import io.github.sudoitir.artemisstudio.kernel.core.PagedView;
import io.github.sudoitir.artemisstudio.kernel.core.ResourceQuery;
import io.github.sudoitir.artemisstudio.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.artemisstudio.kernel.security.Permissions;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerConnectionException;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerListOps;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerListOps.ListPage;
import io.github.sudoitir.artemisstudio.platform.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterDirectory;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Live-through fan-out for the five point-in-time resource views (addresses,
 * consumers, sessions, connections, producers) — ADR-0017.
 *
 * <p>One batched POST per serving node (the {@code -1/-1} full page), rows tagged
 * with their logical node, then merged / filtered / sorted / paged in memory. A
 * node that errors contributes nothing and is reflected only by a shorter list —
 * it is not an error unless <em>every</em> node failed, in which case the first
 * classified failure is rethrown for the UI to render (capability ledger +
 * {@code broker.xml} advice, non-negotiable #5).
 */
@Service
@RequiredArgsConstructor
public class PagedListService {

    private final ClusterDirectory nodes;
    private final BrokerConnections connections;
    private final BrokerListOps listOps;
    private final ResourceViewMapper mapper;
    private final ClusterAccessGuard clusterAccess;

    @Transactional(readOnly = true)
    public PagedView<AddressView> addresses(UUID clusterId, ResourceQuery query) {
        return fanOut(clusterId, ResourceKind.ADDRESSES, query, mapper::address, AddressView::name, nameComparator());
    }

    @Transactional(readOnly = true)
    public PagedView<ConsumerView> consumers(UUID clusterId, ResourceQuery query) {
        return fanOut(
                clusterId,
                ResourceKind.CONSUMERS,
                query,
                mapper::consumer,
                ConsumerView::queueName,
                Comparator.comparing(ConsumerView::queueName, nullSafe()));
    }

    @Transactional(readOnly = true)
    public PagedView<SessionView> sessions(UUID clusterId, ResourceQuery query) {
        return fanOut(
                clusterId,
                ResourceKind.SESSIONS,
                query,
                mapper::session,
                SessionView::sessionId,
                Comparator.comparing(SessionView::sessionId, nullSafe()));
    }

    @Transactional(readOnly = true)
    public PagedView<ConnectionView> connections(UUID clusterId, ResourceQuery query) {
        return fanOut(
                clusterId,
                ResourceKind.CONNECTIONS,
                query,
                mapper::connection,
                ConnectionView::remoteAddress,
                Comparator.comparing(ConnectionView::remoteAddress, nullSafe()));
    }

    @Transactional(readOnly = true)
    public PagedView<ProducerView> producers(UUID clusterId, ResourceQuery query) {
        return fanOut(
                clusterId,
                ResourceKind.PRODUCERS,
                query,
                mapper::producer,
                ProducerView::address,
                Comparator.comparing(ProducerView::address, nullSafe()));
    }

    private <T> PagedView<T> fanOut(
            UUID clusterId,
            ResourceKind kind,
            ResourceQuery query,
            BiFunction<JsonNode, NodeRef, T> rowMapper,
            Function<T, String> filterField,
            Comparator<T> comparator) {
        // Every one of the five public reads routes through here, so the scope check
        // lives here too — a sixth kind cannot be added without inheriting it.
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_READ);
        List<ClusterNode> servingNodes = servingManageableNodes(clusterId);
        if (servingNodes.isEmpty()) {
            throw new BrokerConnectionException(
                    BrokerConnectionException.Kind.UNREACHABLE,
                    "This cluster has no reachable node with a management URL.");
        }

        List<T> merged = new ArrayList<>();
        BrokerConnectionException firstError = null;
        for (ClusterNode node : servingNodes) {
            try {
                JolokiaBrokerClient client = connections.forCluster(clusterId, node.getJolokiaUrl());
                ListPage page = listOps.fetch(client, kind.op(), "", -1, -1);
                if (page.data() != null && page.data().isArray()) {
                    NodeRef ref = new NodeRef(node.getId(), node.getName());
                    page.data().forEach(row -> merged.add(rowMapper.apply(row, ref)));
                }
            } catch (BrokerConnectionException e) {
                if (firstError == null) {
                    firstError = e;
                }
            }
        }
        if (merged.isEmpty() && firstError != null) {
            throw firstError;
        }

        List<T> filtered = merged.stream()
                .filter(row -> query.matches(filterField.apply(row)))
                .toList();
        return query.paginate(filtered, comparator);
    }

    /** One manageable endpoint per NodeID — the active one when the pair reports one. */
    private List<ClusterNode> servingManageableNodes(UUID clusterId) {
        Map<String, ClusterNode> perNodeId = new LinkedHashMap<>();
        for (ClusterNode n : nodes.nodes(clusterId)) {
            if (n.getJolokiaUrl() == null) {
                continue;
            }
            String key = n.getArtemisNodeId() != null ? n.getArtemisNodeId() : "id:" + n.getId();
            perNodeId.merge(key, n, (kept, candidate) -> Boolean.TRUE.equals(candidate.getActive()) ? candidate : kept);
        }
        return List.copyOf(perNodeId.values());
    }

    private static Comparator<AddressView> nameComparator() {
        return Comparator.comparing(AddressView::name, nullSafe());
    }

    private static Comparator<String> nullSafe() {
        return Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER);
    }
}
