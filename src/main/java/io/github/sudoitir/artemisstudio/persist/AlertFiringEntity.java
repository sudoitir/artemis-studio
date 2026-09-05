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
import lombok.Setter;

/**
 * Maps {@code alert_firing} (changeset 013): the append-only start/resolve
 * history {@code alert_state} alone cannot hold, since that row is overwritten
 * in place on every transition (ADR-0035).
 */
@Entity
@Table(name = "alert_firing")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class AlertFiringEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "seq", nullable = false, updatable = false)
    @EqualsAndHashCode.Include
    private Long seq;

    @Column(name = "cluster_id", nullable = false, updatable = false)
    private UUID clusterId;

    @Column(name = "rule_id", nullable = false, updatable = false)
    private UUID ruleId;

    @Column(name = "subject_key", nullable = false, updatable = false)
    private String subjectKey;

    @Column(name = "severity", nullable = false, updatable = false)
    private String severity;

    @Column(name = "value")
    private Double value;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    public AlertFiringEntity(
            UUID clusterId, UUID ruleId, String subjectKey, String severity, Double value, Instant startedAt) {
        this.clusterId = clusterId;
        this.ruleId = ruleId;
        this.subjectKey = subjectKey;
        this.severity = severity;
        this.value = value;
        this.startedAt = startedAt;
    }

    public void resolve(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }
}
