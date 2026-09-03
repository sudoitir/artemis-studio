package io.github.sudoitir.artemisstudio.sse;

import io.github.sudoitir.artemisstudio.broker.QueueRow;
import io.github.sudoitir.artemisstudio.domain.topology.NodeEndpoint;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Emits SSE change signals only when the scrape actually changed the persisted
 * state (ADR-0018 — "emitted only on a real change"). Keeps a cheap per-cluster
 * signature so a broker that reports the same numbers every tick produces no
 * stream traffic beyond the heartbeat.
 */
@Component
@RequiredArgsConstructor
public class StreamSignals {

    private final SseHub hub;

    private final Map<UUID, String> topologySignature = new ConcurrentHashMap<>();
    private final Map<UUID, Long> queueSignature = new ConcurrentHashMap<>();

    /** After a tier-A tick: publish topology + health if the endpoint set / roles / liveness moved. */
    public void afterTierA(UUID clusterId, List<NodeEndpoint> endpoints) {
        String signature = endpoints.stream()
                .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
                .map(e -> e.artemisNodeId() + "|" + e.haRole() + "|" + e.state() + "|" + e.active() + "|"
                        + e.replicaSync() + "|" + (e.lastError() != null))
                .collect(Collectors.joining(";"));
        String previous = topologySignature.put(clusterId, signature);
        if (!signature.equals(previous)) {
            hub.publish(clusterId, "topology");
            hub.publish(clusterId, "health");
        }
    }

    /** After a tier-B/C queue scrape: publish queues if any counter in the page moved. */
    public void afterQueueScrape(UUID clusterId, List<QueueRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        long signature = rows.size() * 1_000_003L
                + rows.stream()
                        .mapToLong(r -> r.messageCount() + r.consumerCount() + r.deliveringCount() + r.scheduledCount())
                        .sum();
        Long previous = queueSignature.put(clusterId, signature);
        if (previous == null || previous != signature) {
            hub.publish(clusterId, "queues");
        }
    }

    public void forget(UUID clusterId) {
        topologySignature.remove(clusterId);
        queueSignature.remove(clusterId);
    }
}
