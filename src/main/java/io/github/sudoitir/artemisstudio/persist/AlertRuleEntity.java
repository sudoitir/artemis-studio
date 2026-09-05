package io.github.sudoitir.artemisstudio.persist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps {@code alert_rule} (changesets 006, 013). Either a metric-threshold rule
 * ({@code metric}/{@code comparator}/{@code threshold} set) or a state-condition
 * rule ({@code stateCondition} set) — never both, enforced by
 * {@code ck_alert_rule_kind_shape} (ADR-0035).
 */
@Entity
@Table(name = "alert_rule")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class AlertRuleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    @EqualsAndHashCode.Include
    private UUID id;

    @Column(name = "cluster_id", updatable = false)
    private UUID clusterId;

    @Column(name = "name", nullable = false)
    private String name = "";

    @Column(name = "kind", nullable = false)
    private String kind;

    @Column(name = "metric")
    private String metric;

    @Column(name = "comparator")
    private String comparator;

    @Column(name = "threshold")
    private Double threshold;

    @Column(name = "state_condition")
    private String stateCondition;

    @Column(name = "for_seconds", nullable = false)
    private int forSeconds;

    @Column(name = "severity", nullable = false)
    private String severity = "WARNING";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "scope")
    private String scope;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static AlertRuleEntity threshold(
            UUID clusterId,
            String name,
            String metric,
            String comparator,
            double threshold,
            int forSeconds,
            String severity,
            String scope) {
        AlertRuleEntity r = new AlertRuleEntity();
        r.clusterId = clusterId;
        r.name = name;
        r.kind = "METRIC_THRESHOLD";
        r.metric = metric;
        r.comparator = comparator;
        r.threshold = threshold;
        r.forSeconds = forSeconds;
        r.severity = severity;
        r.scope = scope;
        return r;
    }

    public static AlertRuleEntity state(
            UUID clusterId, String name, String stateCondition, int forSeconds, String severity) {
        AlertRuleEntity r = new AlertRuleEntity();
        r.clusterId = clusterId;
        r.name = name;
        r.kind = "STATE";
        r.stateCondition = stateCondition;
        r.forSeconds = forSeconds;
        r.severity = severity;
        return r;
    }

    public boolean isThreshold() {
        return "METRIC_THRESHOLD".equals(kind);
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
