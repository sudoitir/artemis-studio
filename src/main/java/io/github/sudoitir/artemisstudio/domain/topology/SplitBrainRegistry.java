package io.github.sudoitir.artemisstudio.domain.topology;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The last split-brain verdict per cluster, keyed by NodeID. Written only by the
 * scrape schedule ({@code ScrapeCycle} corroborates, then publishes here); read
 * by {@code /topology} and {@code /health} through {@link HaStateEvaluator}.
 *
 * <p>Split into its own holder so the read path depends on {@code domain.topology}
 * and never on {@code scheduler} — reads cannot advance the corroboration
 * ratchet (ADR-0015: "reading health does not corroborate").
 *
 * <p>In memory only. A restart clears it; the next tier-A cycle repopulates it,
 * so a real split-brain re-escalates within ~one extra cycle.
 */
@Component
public class SplitBrainRegistry {

    private final Map<UUID, Map<String, SplitBrainStatus>> byCluster = new ConcurrentHashMap<>();

    /** Scheduler-only: replace a cluster's verdicts with the latest corroboration pass. */
    public void publish(UUID clusterId, Map<String, SplitBrainStatus> statusesByNodeId) {
        byCluster.put(clusterId, Map.copyOf(statusesByNodeId));
    }

    /** All verdicts for a cluster, keyed by NodeID. Empty until the first corroboration pass. */
    public Map<String, SplitBrainStatus> statusesFor(UUID clusterId) {
        return byCluster.getOrDefault(clusterId, Map.of());
    }

    public SplitBrainStatus statusFor(UUID clusterId, String nodeId) {
        return statusesFor(clusterId).getOrDefault(nodeId, SplitBrainStatus.NONE);
    }

    public void forget(UUID clusterId) {
        byCluster.remove(clusterId);
    }
}
