package io.github.sudoitir.artemisstudio.mapper;

import io.github.sudoitir.artemisstudio.persist.QueueSnapshotEntity;
import io.github.sudoitir.artemisstudio.web.dto.ResourceViews.QueueNodeCell;
import io.github.sudoitir.artemisstudio.web.dto.ResourceViews.QueueView;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code queue_snapshot} rows sharing an {@code (address, queueName, routingType)}
 * key → one {@link QueueView}: a per-node cell for each row, cluster totals, and
 * node presence (ADR-0017).
 */
@Component
public class QueueViewMapper {

    /** The aggregation key — a logical queue across the cluster. */
    public record QueueKey(String address, String queueName, String routingType) {}

    public QueueView toView(
            QueueKey key,
            List<QueueSnapshotEntity> rows,
            Map<UUID, String> nodeNames,
            int nodesTotal,
            Instant staleBefore) {
        List<QueueNodeCell> cells = rows.stream()
                .map(r -> new QueueNodeCell(
                        r.getNodeId(),
                        nodeNames.getOrDefault(r.getNodeId(), r.getNodeId().toString()),
                        r.getTs() != null && r.getTs().isBefore(staleBefore),
                        r.getTs(),
                        r.getMessageCount(),
                        r.getConsumerCount(),
                        r.getDeliveringCount(),
                        r.getScheduledCount()))
                .sorted((a, b) -> a.nodeName().compareToIgnoreCase(b.nodeName()))
                .toList();

        return new QueueView(
                key.address(),
                key.queueName(),
                key.routingType(),
                rows.stream().anyMatch(QueueSnapshotEntity::isDurable),
                cells.stream().mapToLong(QueueNodeCell::messageCount).sum(),
                cells.stream().mapToLong(QueueNodeCell::consumerCount).sum(),
                cells.stream().mapToLong(QueueNodeCell::deliveringCount).sum(),
                cells.stream().mapToLong(QueueNodeCell::scheduledCount).sum(),
                (int) cells.stream().map(QueueNodeCell::nodeId).distinct().count(),
                nodesTotal,
                cells);
    }
}
