package io.github.sudoitir.artemisstudio.service;

import io.github.sudoitir.artemisstudio.broker.BrokerConnectionException;
import io.github.sudoitir.artemisstudio.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.broker.BrokerListOps;
import io.github.sudoitir.artemisstudio.broker.BrokerListOps.ListPage;
import io.github.sudoitir.artemisstudio.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.mapper.ResourceViewMapper;
import io.github.sudoitir.artemisstudio.mapper.ResourceViewMapper.NodeRef;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeRepository;
import io.github.sudoitir.artemisstudio.scheduler.NodeCallLimiter;
import io.github.sudoitir.artemisstudio.web.dto.ResourceViews.AddressView;
import io.github.sudoitir.artemisstudio.web.dto.ResourceViews.ConnectionView;
import io.github.sudoitir.artemisstudio.web.dto.ResourceViews.ConsumerView;
import io.github.sudoitir.artemisstudio.web.dto.ResourceViews.PagedView;
import io.github.sudoitir.artemisstudio.web.dto.ResourceViews.ProducerView;
import io.github.sudoitir.artemisstudio.web.dto.ResourceViews.SessionView;
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

    private final BrokerNodeRepository nodes;
    private final BrokerConnections connections;
    private final BrokerListOps listOps;
    private final ResourceViewMapper mapper;
    private final NodeCallLimiter limiter;

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
        List<BrokerNodeEntity> servingNodes = servingManageableNodes(clusterId);
        if (servingNodes.isEmpty()) {
            throw new BrokerConnectionException(
                    BrokerConnectionException.Kind.UNREACHABLE,
                    "This cluster has no reachable node with a management URL.");
        }

        List<T> merged = new ArrayList<>();
        BrokerConnectionException firstError = null;
        for (BrokerNodeEntity node : servingNodes) {
            try {
                limiter.acquire(node.getId());
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
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BrokerConnectionException(
                        BrokerConnectionException.Kind.UNREACHABLE, "Timed out waiting for a per-node call permit.");
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

    private static Comparator<AddressView> nameComparator() {
        return Comparator.comparing(AddressView::name, nullSafe());
    }

    private static Comparator<String> nullSafe() {
        return Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER);
    }
}
