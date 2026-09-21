package io.github.sudoitir.artemisstudio.feature.bulk.internal.persistence;

import io.github.sudoitir.artemisstudio.feature.bulk.BulkOperation;
import io.github.sudoitir.artemisstudio.feature.bulk.BulkRunStatus;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One bulk run (ADR-0093): an operation over a set of queues frozen at preview. */
@Entity
@Table(name = "bulk_run")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class BulkRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    @EqualsAndHashCode.Include
    private UUID id;

    @Column(name = "cluster_id", nullable = false, updatable = false)
    private UUID clusterId;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation", nullable = false, updatable = false)
    private BulkOperation operation;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private BulkRunStatus status = BulkRunStatus.PREVIEWED;

    /** Who previewed it, then who executed it. */
    @Column(name = "username", nullable = false)
    private String username;

    @Column(name = "plan_hash", nullable = false, updatable = false)
    private String planHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "selection", nullable = false, updatable = false)
    private String selection;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "options", nullable = false, updatable = false)
    private String options;

    @Column(name = "total_items", nullable = false, updatable = false)
    private int totalItems;

    @Column(name = "succeeded", nullable = false)
    private int succeeded;

    @Column(name = "failed", nullable = false)
    private int failed;

    @Column(name = "skipped", nullable = false)
    private int skipped;

    @Column(name = "estimate", updatable = false)
    private Long estimate;

    @Column(name = "estimate_complete", nullable = false, updatable = false)
    private boolean estimateComplete;

    @Column(name = "override_cap", nullable = false)
    private boolean overrideCap;

    @Column(name = "continue_on_failure", nullable = false)
    private boolean continueOnFailure;

    @Column(name = "audit_event_id")
    private Long auditEventId;

    @Column(name = "error")
    private String error;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    public BulkRunEntity(
            UUID clusterId,
            BulkOperation operation,
            String username,
            String planHash,
            String selection,
            String options,
            int totalItems,
            int refused,
            Long estimate,
            boolean estimateComplete,
            Instant createdAt,
            Instant expiresAt) {
        this.clusterId = clusterId;
        this.operation = operation;
        this.username = username;
        this.planHash = planHash;
        this.selection = selection;
        this.options = options;
        this.totalItems = totalItems;
        this.skipped = refused;
        this.estimate = estimate;
        this.estimateComplete = estimateComplete;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public void start(String username, boolean overrideCap, boolean continueOnFailure, Instant now) {
        this.status = BulkRunStatus.RUNNING;
        this.username = username;
        this.overrideCap = overrideCap;
        this.continueOnFailure = continueOnFailure;
        this.startedAt = now;
    }

    public void attachAudit(Long auditEventId) {
        this.auditEventId = auditEventId;
    }

    public void count(int succeeded, int failed, int skipped) {
        this.succeeded = succeeded;
        this.failed = failed;
        this.skipped = skipped;
    }

    public void finish(BulkRunStatus status, String error, Instant now) {
        this.status = status;
        this.error = error;
        this.finishedAt = now;
    }
}
