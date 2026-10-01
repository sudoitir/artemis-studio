package io.github.sudoitir.artemisstudio.platform.broker;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The replicas of this installation and the clusters each owns, as the health view needs to see them.
 * Implemented by the module that owns cluster ownership, so the transport never reads its tables.
 */
public interface ReplicaDirectory {

    /** This replica. */
    UUID self();

    /** Every replica seen recently, including stopped and gone ones, oldest first. */
    List<KnownReplica> replicas();

    /**
     * One replica. {@code state} is {@code starting}, {@code ready}, {@code draining} or {@code stopped}, and
     * {@code gone} says it has not stopped but has not sent a heartbeat within the ttl.
     */
    record KnownReplica(
            UUID id,
            String host,
            String version,
            String state,
            Instant startedAt,
            long heartbeatAgeMillis,
            boolean gone,
            List<OwnedCluster> clusters) {}

    record OwnedCluster(UUID id, String name) {}
}
