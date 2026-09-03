package io.github.sudoitir.artemisstudio.domain.topology;

import java.time.Instant;
import java.util.UUID;

/**
 * One broker endpoint — a single {@code broker_node} row. A synced backup and its
 * primary are two endpoints of the same {@link LogicalNode}, because a synced
 * backup adopts the primary's NodeID.
 *
 * @param manageable whether Studio has a Jolokia URL for this endpoint and can
 *     therefore act on it. A discovered endpoint with only a broker-to-broker
 *     {@code coreUrl} is known but not yet manageable — the common containerised
 *     case (Phase 0), presented as a next step, not an error.
 */
public record NodeEndpoint(
        UUID id,
        String name,
        String artemisNodeId,
        String jolokiaUrl,
        String coreUrl,
        String haRole,
        String state,
        boolean active,
        Boolean replicaSync,
        Long observedCycle,
        String version,
        String lastError,
        Instant lastSeenAt,
        boolean discovered,
        boolean manualOverride,
        boolean manageable) {

    public boolean isBackup() {
        return "BACKUP".equals(haRole);
    }

    public boolean isStarted() {
        return "STARTED".equals(state);
    }

    /**
     * Whether this endpoint is serving traffic <em>right now</em>. A stale
     * {@code active=true} left behind by a node that has since gone unreachable
     * ({@code lastError} set) does not count — otherwise a clean failover would
     * show both sides live.
     */
    public boolean live() {
        return active && lastError == null;
    }

    public boolean unreachable() {
        return lastError != null;
    }
}
