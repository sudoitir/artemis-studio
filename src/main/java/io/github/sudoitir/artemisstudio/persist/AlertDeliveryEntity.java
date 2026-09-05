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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps {@code alert_delivery} (changeset 013): the durable retry queue and
 * ledger for one notification, batched per {@code (rule, channel)} per
 * evaluation tick — never one row per firing subject (ADR-0036, design.md
 * decision 5). {@link io.github.sudoitir.artemisstudio.scheduler.AlertDispatcher}
 * is the only writer of {@code state}/{@code attempts}/{@code lastError}.
 */
@Entity
@Table(name = "alert_delivery")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class AlertDeliveryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "seq", nullable = false, updatable = false)
    @EqualsAndHashCode.Include
    private Long seq;

    @Column(name = "rule_id", nullable = false, updatable = false)
    private UUID ruleId;

    @Column(name = "channel_id", nullable = false, updatable = false)
    private UUID channelId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    @Column(name = "state", nullable = false)
    private String state = "PENDING";

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    public AlertDeliveryEntity(UUID ruleId, UUID channelId, String payload) {
        this.ruleId = ruleId;
        this.channelId = channelId;
        this.payload = payload;
        this.state = "PENDING";
        Instant now = Instant.now();
        this.createdAt = now;
        this.nextAttemptAt = now;
    }

    public void recordSuccess(Instant now) {
        this.state = "SENT";
        this.deliveredAt = now;
        this.lastError = null;
    }

    /** Backs off via the given delay, or gives up to {@code DEAD} at {@code maxAttempts}. */
    public void recordFailure(Instant now, String error, java.time.Duration nextDelay, int maxAttempts) {
        this.attempts++;
        this.lastError = error;
        if (this.attempts >= maxAttempts) {
            this.state = "DEAD";
        } else {
            this.nextAttemptAt = now.plus(nextDelay);
        }
    }

    /** A permanently-invalid destination (e.g. a revoked Slack webhook) — no retry. */
    public void recordDead(String error) {
        this.attempts++;
        this.lastError = error;
        this.state = "DEAD";
    }
}
