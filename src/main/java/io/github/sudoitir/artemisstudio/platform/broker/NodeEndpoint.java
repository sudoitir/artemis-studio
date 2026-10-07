package io.github.sudoitir.artemisstudio.platform.broker;

import java.time.Instant;
import java.util.UUID;

/**
 * One broker endpoint — a single {@code broker_node} row. A synced backup and its
 * primary are two endpoints of the same {@code LogicalNode}, because a synced
 * backup adopts the primary's NodeID.
 *
 * @param manageable whether Studio has a Jolokia URL for this endpoint and can
 *     therefore act on it. A discovered endpoint with only a broker-to-broker
 *     {@code coreUrl} is known but not yet manageable — the common containerised
 *     case (Phase 0), presented as a next step, not an error.
 * @param lastErrorKind the class of {@code lastError}, so a rejected credential is not read as an
 *     unreachable broker
 * @param urlSource where the management URL came from; {@code null} while there is none
 * @param urlProblem why there is no management URL, once an attempt to derive one has failed
 * @param coreUrlManual whether an operator set the Core URL, so discovery must leave it alone
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
        BrokerConnectionException.Kind lastErrorKind,
        Instant lastSeenAt,
        ManagementUrlSource urlSource,
        ManagementUrlProblem urlProblem,
        boolean coreUrlManual,
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

    /** Whether the broker's last answer refused the management account, which is not the same as no answer. */
    public boolean credentialsRejected() {
        return lastErrorKind == BrokerConnectionException.Kind.CREDENTIALS_REJECTED;
    }
}
