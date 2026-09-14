package io.github.sudoitir.artemisstudio.platform.clusters;

import java.time.Instant;
import java.util.UUID;

/**
 * A registered broker node as other modules read it: identity, endpoints and the last observed
 * HA state. Nodes are written only by this module; see {@link ClusterDirectory}.
 */
public interface ClusterNode {

    UUID getId();

    UUID getClusterId();

    String getName();

    /** {@code null} for a node Studio cannot manage yet. */
    String getJolokiaUrl();

    String getCoreUrl();

    /** The broker's own NodeID, shared by a live/backup pair; {@code null} until first read. */
    String getArtemisNodeId();

    /** Whether the node reported itself live at the last read; {@code null} before any read. */
    Boolean getActive();

    String getLastError();

    Boolean getReplicaSync();

    /** {@code PRIMARY}, {@code BACKUP} or {@code STANDALONE}. */
    String getHaRole();

    String getState();

    String getVersion();

    Instant getLastSeenAt();

    /** The scrape cycle the HA state was last observed in; {@code null} before any read. */
    Long getObservedCycle();

    /** Found through topology discovery rather than given as a seed. */
    boolean isDiscovered();

    /** Its endpoints were set by an operator and discovery must not replace them. */
    boolean isManualOverride();
}
