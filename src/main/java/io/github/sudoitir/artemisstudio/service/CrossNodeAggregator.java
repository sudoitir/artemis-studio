package io.github.sudoitir.artemisstudio.service;

import io.github.sudoitir.artemisstudio.config.ArtemisStudioProperties;
import io.github.sudoitir.artemisstudio.mapper.QueueViewMapper;
import io.github.sudoitir.artemisstudio.mapper.QueueViewMapper.QueueKey;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeRepository;
import io.github.sudoitir.artemisstudio.persist.QueueSnapshotEntity;
import io.github.sudoitir.artemisstudio.persist.QueueSnapshotRepository;
import io.github.sudoitir.artemisstudio.web.dto.ResourceViews.PagedView;
import io.github.sudoitir.artemisstudio.web.dto.ResourceViews.QueueView;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the cross-node queue grid from {@code queue_snapshot} (ADR-0017). A
 * primary and its synced backup share a NodeID and are one logical node; the
 * scrape only ever writes the live endpoint's rows, so there is no double count.
 * A node whose last sweep is stale keeps its last numbers, flagged — never
 * dropped.
 */
@Service
@RequiredArgsConstructor
public class CrossNodeAggregator {

    private final QueueSnapshotRepository snapshots;
    private final BrokerNodeRepository nodes;
    private final QueueViewMapper mapper;
    private final ArtemisStudioProperties properties;

    @Transactional(readOnly = true)
    public PagedView<QueueView> queues(UUID clusterId, ResourceQuery query) {
        List<BrokerNodeEntity> nodeRows = nodes.findByClusterIdOrderByNameAsc(clusterId);
        Map<UUID, String> nodeNames =
                nodeRows.stream().collect(Collectors.toMap(BrokerNodeEntity::getId, BrokerNodeEntity::getName));
        int nodesTotal = (int) nodeRows.stream()
                .map(CrossNodeAggregator::logicalKey)
                .distinct()
                .count();
        Instant staleBefore =
                Instant.now().minus(properties.scrape().tierCInterval().multipliedBy(2));

        Map<QueueKey, List<QueueSnapshotEntity>> byKey = snapshots.findByClusterId(clusterId).stream()
                .collect(Collectors.groupingBy(
                        s -> new QueueKey(s.getAddress(), s.getQueueName(), s.getRoutingType()),
                        LinkedHashMap::new,
                        Collectors.toList()));

        List<QueueView> rows = byKey.entrySet().stream()
                .map(e -> mapper.toView(e.getKey(), e.getValue(), nodeNames, nodesTotal, staleBefore))
                .filter(v -> query.matches(v.queueName()) || query.matches(v.address()))
                .toList();

        return query.paginate(rows, comparatorFor(query.sortField()));
    }

    private static String logicalKey(BrokerNodeEntity n) {
        return n.getArtemisNodeId() != null ? n.getArtemisNodeId() : "id:" + n.getId();
    }

    private static Comparator<QueueView> comparatorFor(String field) {
        if (field == null) {
            return null;
        }
        return switch (field) {
            case "queueName", "name" -> Comparator.comparing(QueueView::queueName, String.CASE_INSENSITIVE_ORDER);
            case "address" -> Comparator.comparing(QueueView::address, String.CASE_INSENSITIVE_ORDER);
            case "depth", "messageCount" -> Comparator.comparingLong(QueueView::totalMessageCount);
            case "consumers", "consumerCount" -> Comparator.comparingLong(QueueView::totalConsumerCount);
            case "delivering" -> Comparator.comparingLong(QueueView::totalDeliveringCount);
            case "scheduled" -> Comparator.comparingLong(QueueView::totalScheduledCount);
            default -> null;
        };
    }
}
