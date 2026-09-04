package io.github.sudoitir.artemisstudio.persist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Maps {@code rr_expectation} (changesets 007, 011): which request addresses an
 * operator has declared for request-reply tracing, and how (proposal.md,
 * request-reply-tracing spec).
 */
@Entity
@Table(name = "rr_expectation")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class RrExpectationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    @EqualsAndHashCode.Include
    private UUID id;

    @Column(name = "cluster_id", nullable = false, updatable = false)
    private UUID clusterId;

    @Column(name = "request_address", nullable = false)
    private String requestAddress;

    @Column(name = "reply_address")
    private String replyAddress;

    @Column(name = "correlation_property")
    private String correlationProperty;

    @Column(name = "deadline_ms")
    private Integer deadlineMs;

    @Column(name = "sample_per_min", nullable = false)
    private int samplePerMin = 10;

    @Column(name = "capture_payload", nullable = false)
    private boolean capturePayload;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    public RrExpectationEntity(
            UUID clusterId,
            String requestAddress,
            String replyAddress,
            String correlationProperty,
            Integer deadlineMs,
            int samplePerMin,
            boolean capturePayload) {
        this.clusterId = clusterId;
        this.requestAddress = requestAddress;
        this.replyAddress = replyAddress;
        this.correlationProperty = correlationProperty;
        this.deadlineMs = deadlineMs;
        this.samplePerMin = samplePerMin;
        this.capturePayload = capturePayload;
        this.enabled = true;
    }
}
