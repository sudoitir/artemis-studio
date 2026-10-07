package io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence;

import io.github.sudoitir.artemisstudio.platform.broker.BrokerConnectionException;
import io.github.sudoitir.artemisstudio.platform.broker.ManagementUrlProblem;
import io.github.sudoitir.artemisstudio.platform.broker.ManagementUrlSource;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterNode;
import io.github.sudoitir.artemisstudio.platform.clusters.SplitBrainStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Maps the {@code broker_node} table. One row per broker
 * endpoint. HA state is written by dirty-checking these fields (ADR-0011), so the
 * row's {@code id} never changes and {@code audit_event.node_id} stays valid.
 */
@Entity
@Table(name = "broker_node")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class BrokerNodeEntity implements ClusterNode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    @EqualsAndHashCode.Include
    private UUID id;

    @Column(name = "cluster_id", nullable = false, updatable = false)
    private UUID clusterId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "jolokia_url")
    private String jolokiaUrl;

    @Column(name = "core_url")
    private String coreUrl;

    @Column(name = "ha_role", nullable = false)
    private String haRole = "STANDALONE";

    @Column(name = "pair_group")
    private String pairGroup;

    @Column(name = "state", nullable = false)
    private String state = "UNKNOWN";

    @Column(name = "version")
    private String version;

    @Column(name = "artemis_node_id")
    private String artemisNodeId;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "url_checked_at")
    private Instant urlCheckedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "url_source")
    private ManagementUrlSource urlSource;

    @Enumerated(EnumType.STRING)
    @Column(name = "url_problem")
    private ManagementUrlProblem urlProblem;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_error_kind")
    private BrokerConnectionException.Kind lastErrorKind;

    @Column(name = "core_url_manual", nullable = false)
    private boolean coreUrlManual;

    @Column(name = "active")
    private Boolean active;

    @Column(name = "replica_sync")
    private Boolean replicaSync;

    @Column(name = "observed_cycle")
    private Long observedCycle;

    @Enumerated(EnumType.STRING)
    @Column(name = "split_brain", nullable = false)
    private SplitBrainStatus splitBrain = SplitBrainStatus.NONE;

    @Column(name = "clock_measured_at")
    private Instant clockMeasuredAt;

    @Column(name = "clock_offset_ms")
    private Long clockOffsetMs;

    @Column(name = "clock_uncertainty_ms")
    private Integer clockUncertaintyMs;

    /** A row learned from {@code listNetworkTopology()} — connector-named, no management URL yet. */
    public static BrokerNodeEntity discovered(UUID clusterId, String connector, String haRole, String nodeId) {
        BrokerNodeEntity n = new BrokerNodeEntity();
        n.clusterId = clusterId;
        n.name = connector;
        n.coreUrl = connector;
        n.haRole = haRole;
        n.pairGroup = nodeId;
        n.artemisNodeId = nodeId;
        return n;
    }

    /** A row created directly from a registration seed (no matching topology entry). */
    public static BrokerNodeEntity fromSeed(UUID clusterId, String name, String haRole, String nodeId) {
        BrokerNodeEntity n = new BrokerNodeEntity();
        n.clusterId = clusterId;
        n.name = name;
        n.haRole = haRole;
        n.pairGroup = nodeId;
        n.artemisNodeId = nodeId;
        return n;
    }

    /** Discovery merge: enrich a row with what the topology reports, leaving a manual Core URL and the management URL as they are. */
    public void mergeDiscovered(String coreUrl, String haRole, String nodeId) {
        if (coreUrl != null && !coreUrlManual) {
            this.coreUrl = coreUrl;
        }
        this.haRole = haRole;
        this.pairGroup = nodeId;
        if (nodeId != null) {
            this.artemisNodeId = nodeId;
        }
    }

    /**
     * A management URL the operator gave as a seed. A URL already set manually stays manual; any other
     * source becomes {@code SEED}, because the operator has now named it.
     */
    public void attachSeedUrl(String jolokiaUrl) {
        this.jolokiaUrl = jolokiaUrl;
        if (urlSource != ManagementUrlSource.MANUAL) {
            this.urlSource = ManagementUrlSource.SEED;
        }
        this.urlProblem = null;
    }

    /** A management URL built from the cluster's pattern and proved by the broker's NodeID (ADR-0175). */
    public void attachDerivedUrl(String jolokiaUrl) {
        this.jolokiaUrl = jolokiaUrl;
        this.urlSource = ManagementUrlSource.DERIVED;
        this.urlProblem = null;
        this.urlCheckedAt = null;
    }

    /** The attempt to derive a management URL failed: the node has none, and says why. */
    public void recordUrlProblem(ManagementUrlProblem problem, Instant checkedAt) {
        this.jolokiaUrl = null;
        this.urlSource = null;
        this.urlProblem = problem;
        this.urlCheckedAt = checkedAt;
    }

    /** The account, the pattern or the TLS bundle changed, so the reason a URL was not derived may no longer hold. */
    public void clearUrlProblem() {
        this.urlProblem = null;
        this.urlCheckedAt = null;
    }

    /** A seed the operator no longer lists: the node keeps no URL until one is derived again. */
    public void releaseSeedUrl() {
        this.jolokiaUrl = null;
        this.urlSource = null;
        this.urlProblem = null;
    }

    /** The {@code PATCH} override: an operator supplies a reachable URL; discovery must never overwrite it. */
    public void applyManualUrl(String jolokiaUrl) {
        this.jolokiaUrl = jolokiaUrl;
        this.urlSource = ManagementUrlSource.MANUAL;
        this.urlProblem = null;
    }

    /**
     * The {@code PATCH} override for the Core URL: discovery stores the
     * broker-advertised connector, which is often unreachable from where Studio
     * runs (ADR-0026). Marks the Core URL manual so discovery leaves it alone.
     */
    public void applyManualCoreUrl(String coreUrl) {
        this.coreUrl = coreUrl;
        this.coreUrlManual = true;
    }

    /** What one HA read of a broker reported; a null {@code version} or {@code artemisNodeId} leaves the stored one. */
    public record HaObservation(
            Boolean active, String state, String haRole, Boolean replicaSync, String version, String artemisNodeId) {}

    /** The refresh loop's write: HA state tagged with the cycle it was observed in (ADR-0012). */
    public void applyHaState(HaObservation observed, long observedCycle, Instant lastSeenAt) {
        this.active = observed.active();
        this.state = observed.state();
        this.haRole = observed.haRole();
        this.replicaSync = observed.replicaSync();
        this.observedCycle = observedCycle;
        if (observed.version() != null) {
            this.version = observed.version();
        }
        if (observed.artemisNodeId() != null) {
            this.artemisNodeId = observed.artemisNodeId();
        }
        this.lastSeenAt = lastSeenAt;
        this.lastError = null;
        this.lastErrorKind = null;
    }

    /** The corroborated split-brain verdict for this node's NodeID. */
    public void recordSplitBrain(SplitBrainStatus status) {
        this.splitBrain = status;
    }

    /** An unanswered tier-A probe: record it without disturbing the last-known-good HA state. */
    public void recordError(Instant seenAt, String error, BrokerConnectionException.Kind kind) {
        this.lastSeenAt = seenAt;
        this.lastError = error;
        this.lastErrorKind = kind;
    }

    /**
     * The measured disagreement between this broker's clock and Studio's (ADR-0053).
     *
     * <p>Persisted so a restart does not begin blind, and so an operator can see the
     * number Studio actually acted on rather than being told a flow timed out for
     * reasons kept in memory.
     */
    public void recordClockOffset(long offsetMs, long uncertaintyMs, Instant measuredAt) {
        this.clockOffsetMs = offsetMs;
        this.clockUncertaintyMs = (int) Math.min(uncertaintyMs, Integer.MAX_VALUE);
        this.clockMeasuredAt = measuredAt;
    }
}
