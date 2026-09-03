package io.github.sudoitir.artemisstudio.domain.topology;

import java.util.List;
import java.util.UUID;

/**
 * The five-second answer: is this cluster healthy, who is live, what is wrong.
 *
 * @param level the rolled-up severity
 * @param liveEndpointNames names of endpoints currently serving traffic
 * @param splitBrain the worst split-brain status across the cluster's pairs
 * @param replicationBehind any pair whose backup is not caught up
 * @param notes human-readable lines, one per abnormal finding; empty when healthy
 */
public record ClusterHealth(
        UUID clusterId,
        Level level,
        List<String> liveEndpointNames,
        SplitBrainStatus splitBrain,
        boolean replicationBehind,
        List<String> notes) {

    public enum Level {
        /** Nothing wrong. Rendered near-monochrome. */
        OK,
        /** Replication behind, a stopped manageable endpoint, or a suspected split-brain. */
        DEGRADED,
        /** Confirmed split-brain. */
        CRITICAL,
        /** No endpoint has ever been contacted. */
        UNKNOWN
    }
}
