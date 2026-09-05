package io.github.sudoitir.artemisstudio.domain.alerting;

import io.github.sudoitir.artemisstudio.persist.AlertRuleEntity;
import io.github.sudoitir.artemisstudio.persist.QueueSnapshotEntity;
import io.github.sudoitir.artemisstudio.persist.QueueSnapshotRepository;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToLongFunction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the latest per-{@code (node,queue)} gauge value from {@code queue_snapshot}
 * (design.md decision 2) — one query per cluster per tick regardless of rule
 * count. A queue present on several nodes without a specific {@code scope.node}
 * is aggregated by summing, keyed {@code queue:<name>}; a node-scoped rule keys
 * {@code node:<artemisNodeId>/queue:<name>} on that node's own value.
 */
@Component
@RequiredArgsConstructor
public class GaugeCondition implements AlertCondition {

    private static final Map<String, ToLongFunction<QueueSnapshotEntity>> GAUGES = Map.of(
            "messageCount", QueueSnapshotEntity::getMessageCount,
            "consumerCount", QueueSnapshotEntity::getConsumerCount,
            "deliveringCount", QueueSnapshotEntity::getDeliveringCount,
            "scheduledCount", QueueSnapshotEntity::getScheduledCount);

    private final QueueSnapshotRepository snapshots;
    private final ObjectMapper mapper;

    public static boolean supports(String metric) {
        return GAUGES.containsKey(metric);
    }

    @Override
    public Evaluation evaluate(UUID clusterId, AlertRuleEntity rule) {
        ToLongFunction<QueueSnapshotEntity> value = GAUGES.get(rule.getMetric());
        if (value == null) {
            return Evaluation.EMPTY;
        }
        AlertScope scope = AlertScope.parse(rule.getScope(), mapper);

        boolean nodeScoped = scope.node() != null && !scope.node().isBlank();
        Set<String> universe = new HashSet<>();
        Map<String, Double> subjectValues = new HashMap<>();
        for (QueueSnapshotEntity row : snapshots.findByClusterId(clusterId)) {
            if (!scope.matchesAddress(row.getAddress()) || !scope.matchesQueue(row.getQueueName())) {
                continue;
            }
            if (nodeScoped && !scope.node().equals(row.getNodeId().toString())) {
                continue;
            }
            String key = nodeScoped
                    ? "node:" + row.getNodeId() + "/queue:" + row.getQueueName()
                    : "queue:" + row.getQueueName();
            universe.add(key);
            subjectValues.merge(key, (double) value.applyAsLong(row), Double::sum);
        }

        Map<String, Double> active = new HashMap<>();
        subjectValues.forEach((subject, v) -> {
            if (Comparators.test(rule.getComparator(), v, rule.getThreshold())) {
                active.put(subject, v);
            }
        });
        return new Evaluation(Set.copyOf(universe), Map.copyOf(active));
    }
}
