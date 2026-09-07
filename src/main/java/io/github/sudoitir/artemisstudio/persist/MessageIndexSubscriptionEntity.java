package io.github.sudoitir.artemisstudio.persist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One operator's decision to capture a queue's messages (changeset 021, ADR-0059).
 * This is estate configuration, not cache: it is the record of a deliberate act, it
 * is audited, and deleting it destroys everything it captured.
 *
 * <p>{@code captureFrom} is the moment capture began. A query reaching before it is
 * outside the index's coverage and gets told so, rather than being answered with a
 * short list.
 */
@Entity
@Table(name = "message_index_subscription")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class MessageIndexSubscriptionEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "cluster_id", nullable = false, updatable = false)
    private UUID clusterId;

    /** An Artemis wildcard pattern, matched the same way a FROM target is. */
    @Column(name = "queue_pattern", nullable = false)
    private String queuePattern;

    @Column(name = "interval_ms", nullable = false)
    private long intervalMs;

    @Column(name = "retention_days", nullable = false)
    private int retentionDays;

    @Column(name = "capture_from", nullable = false)
    private Instant captureFrom;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;
}
