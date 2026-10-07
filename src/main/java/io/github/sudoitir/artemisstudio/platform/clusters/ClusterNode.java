package io.github.sudoitir.artemisstudio.platform.clusters;

import io.github.sudoitir.artemisstudio.platform.broker.BrokerConnectionException;
import io.github.sudoitir.artemisstudio.platform.broker.ManagementUrlProblem;
import io.github.sudoitir.artemisstudio.platform.broker.ManagementUrlSource;
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

    /** The last split-brain verdict for this node's NodeID, written by the replica that owns the cluster. */
    SplitBrainStatus getSplitBrain();

    /** The scrape cycle the HA state was last observed in; {@code null} before any read. */
    Long getObservedCycle();

    /** Where the management URL came from; {@code null} while the node has none. */
    ManagementUrlSource getUrlSource();

    /** Why the node has no management URL; {@code null} once it has one, or before an attempt. */
    ManagementUrlProblem getUrlProblem();

    /** The class of the last probe failure, set with {@link #getLastError()}. */
    BrokerConnectionException.Kind getLastErrorKind();

    /** An operator set the Core URL, so discovery must not replace it. */
    boolean isCoreUrlManual();
}
