package io.github.sudoitir.artemisstudio.persist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps the {@code audit_event} table (changeset 004). One row per mutating
 * action: inserted {@code PENDING} before the broker call, updated to
 * {@code SUCCESS} / {@code FAILURE} after — in the command's own transaction
 * (non-negotiable #3).
 */
@Entity
@Table(name = "audit_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class AuditEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "ts", nullable = false, updatable = false)
    private Instant ts;

    @Column(name = "affected_count")
    private Long affectedCount;

    @Column(name = "action", nullable = false, updatable = false)
    private String action;

    @Column(name = "target_type", updatable = false)
    private String targetType;

    @Column(name = "target_name", updatable = false)
    private String targetName;

    @Column(name = "username", updatable = false)
    private String username;

    @Column(name = "outcome", nullable = false)
    private String outcome = "PENDING";

    @Column(name = "error")
    private String error;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "params", updatable = false)
    private String params;

    @Column(name = "cluster_id", updatable = false)
    private UUID clusterId;

    @Column(name = "node_id", updatable = false)
    private UUID nodeId;

    @Column(name = "dry_run", nullable = false, updatable = false)
    private boolean dryRun;

    public AuditEventEntity(
            String action,
            String targetType,
            String targetName,
            String username,
            UUID clusterId,
            UUID nodeId,
            String params,
            boolean dryRun) {
        this.action = action;
        this.targetType = targetType;
        this.targetName = targetName;
        this.username = username;
        this.clusterId = clusterId;
        this.nodeId = nodeId;
        this.params = params;
        this.dryRun = dryRun;
    }

    @PrePersist
    void onInsert() {
        ts = Instant.now();
    }

    public void markSuccess(long affectedCount) {
        this.outcome = "SUCCESS";
        this.affectedCount = affectedCount;
    }

    public void markFailure(String error) {
        this.outcome = "FAILURE";
        this.error = error;
    }
}
