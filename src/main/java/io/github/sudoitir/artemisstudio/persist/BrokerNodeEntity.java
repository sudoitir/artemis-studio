package io.github.sudoitir.artemisstudio.persist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * Maps the {@code broker_node} table (changesets 002 + 008). One row per broker
 * endpoint. HA state is written by dirty-checking these fields (ADR-0011), so the
 * row's {@code id} never changes and {@code audit_event.node_id} stays valid.
 */
@Entity
@Table(name = "broker_node")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class BrokerNodeEntity {

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

    @Column(name = "discovered", nullable = false)
    private boolean discovered;

    @Column(name = "manual_override", nullable = false)
    private boolean manualOverride;

    @Column(name = "active")
    private Boolean active;

    @Column(name = "replica_sync")
    private Boolean replicaSync;

    @Column(name = "observed_cycle")
    private Long observedCycle;

    /** A row learned from {@code listNetworkTopology()} — connector-named, no management URL yet. */
    public static BrokerNodeEntity discovered(UUID clusterId, String connector, String haRole, String nodeId) {
        BrokerNodeEntity n = new BrokerNodeEntity();
        n.clusterId = clusterId;
        n.name = connector;
        n.coreUrl = connector;
        n.haRole = haRole;
        n.pairGroup = nodeId;
        n.artemisNodeId = nodeId;
        n.discovered = true;
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

    /** Discovery merge: enrich a non-overridden row without touching its management URL. */
    public void mergeDiscovered(String coreUrl, String haRole, String nodeId) {
        if (manualOverride) {
            return;
        }
        if (coreUrl != null) {
            this.coreUrl = coreUrl;
        }
        this.haRole = haRole;
        this.pairGroup = nodeId;
        if (nodeId != null) {
            this.artemisNodeId = nodeId;
        }
    }

    /** A management URL learned at registration; leaves {@code manualOverride} false so discovery may still enrich. */
    public void attachManagementUrl(String jolokiaUrl) {
        this.jolokiaUrl = jolokiaUrl;
        this.discovered = false;
    }

    /** The {@code PATCH} override: an operator supplies a reachable URL; discovery must never overwrite it. */
    public void applyManualUrl(String jolokiaUrl) {
        this.jolokiaUrl = jolokiaUrl;
        this.manualOverride = true;
        this.discovered = false;
    }

    /**
     * The {@code PATCH} override for the Core URL: discovery stores the
     * broker-advertised connector, which is often unreachable from where Studio
     * runs (ADR-0026). Marks the row overridden so rediscovery leaves it alone.
     */
    public void applyManualCoreUrl(String coreUrl) {
        this.coreUrl = coreUrl;
        this.manualOverride = true;
    }

    /** The refresh loop's write: HA state tagged with the cycle it was observed in (ADR-0012). */
    public void applyHaState(
            Boolean active,
            String state,
            String haRole,
            Boolean replicaSync,
            long observedCycle,
            String version,
            String artemisNodeId,
            Instant lastSeenAt) {
        this.active = active;
        this.state = state;
        this.haRole = haRole;
        this.replicaSync = replicaSync;
        this.observedCycle = observedCycle;
        if (version != null) {
            this.version = version;
        }
        if (artemisNodeId != null) {
            this.artemisNodeId = artemisNodeId;
        }
        this.lastSeenAt = lastSeenAt;
        this.lastError = null;
    }

    /** A failed scrape: record it without disturbing the last-known-good HA state. */
    public void recordError(Instant seenAt, String error) {
        this.lastSeenAt = seenAt;
        this.lastError = error;
    }
}
