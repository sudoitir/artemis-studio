package io.github.sudoitir.artemisstudio.persist;

import io.github.sudoitir.artemisstudio.domain.rr.RrState;
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
 * Maps {@code rr_flow} (changesets 007, 011): one reconstructed request-reply
 * flow, in exactly one of the six states the CHECK constraint enumerates
 * (request-reply-tracing spec).
 */
@Entity
@Table(name = "rr_flow")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class RrFlowEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    @EqualsAndHashCode.Include
    private UUID id;

    @Column(name = "cluster_id", nullable = false, updatable = false)
    private UUID clusterId;

    @Column(name = "node_id")
    private UUID nodeId;

    /** Null only for a flow created directly in {@code ORPHANED_REPLY} — the request side was never observed. */
    @Column(name = "request_address", updatable = false)
    private String requestAddress;

    @Column(name = "reply_destination")
    private String replyDestination;

    @Column(name = "reply_kind", nullable = false, updatable = false)
    private String replyKind;

    @Column(name = "state", nullable = false)
    private String state;

    @Column(name = "correlation_id")
    private String correlationId;

    @Column(name = "request_message_id", updatable = false)
    private String requestMessageId;

    @Column(name = "reply_message_id")
    private String replyMessageId;

    @Column(name = "responder_consumer")
    private String responderConsumer;

    @Column(name = "requester_session", updatable = false)
    private String requesterSession;

    @Column(name = "responder_session")
    private String responderSession;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "deadline_at")
    private Instant deadlineAt;

    @Column(name = "replied_at")
    private Instant repliedAt;

    @Column(name = "observed_at")
    private Instant observedAt;

    @Column(name = "latency_ms")
    private Long latencyMs;

    public RrFlowEntity(
            UUID clusterId,
            UUID nodeId,
            String requestAddress,
            String replyDestination,
            String replyKind,
            String state,
            String correlationId,
            String requestMessageId,
            Instant requestedAt,
            Instant deadlineAt) {
        this.clusterId = clusterId;
        this.nodeId = nodeId;
        this.requestAddress = requestAddress;
        this.replyDestination = replyDestination;
        this.replyKind = replyKind;
        this.state = state;
        this.correlationId = correlationId;
        this.requestMessageId = requestMessageId;
        this.requestedAt = requestedAt;
        this.deadlineAt = deadlineAt;
        this.observedAt = requestedAt;
    }

    /** A reply observed with no matching awaiting-reply flow (request-reply-tracing spec, ORPHANED_REPLY). */
    public static RrFlowEntity orphanedReply(
            UUID clusterId,
            UUID nodeId,
            String replyDestination,
            String replyKind,
            String correlationId,
            Instant repliedAt) {
        RrFlowEntity e = new RrFlowEntity();
        e.clusterId = clusterId;
        e.nodeId = nodeId;
        e.requestAddress = null;
        e.replyDestination = replyDestination;
        e.replyKind = replyKind;
        e.state = RrState.ORPHANED_REPLY.name();
        e.correlationId = correlationId;
        e.requestedAt = repliedAt;
        e.repliedAt = repliedAt;
        e.observedAt = repliedAt;
        return e;
    }
}
