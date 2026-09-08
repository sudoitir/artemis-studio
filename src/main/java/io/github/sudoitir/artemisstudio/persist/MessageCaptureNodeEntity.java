package io.github.sudoitir.artemisstudio.persist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Capture state for one subscription on one node (changeset 022, ADR-0062 D4).
 *
 * <p>There is deliberately no cluster-wide aggregate of this. A promoted backup was
 * live and uncaptured between promotion and the next reconcile pass, and a node whose
 * broker refuses the tap is not covered at all; both are facts about a node, and a
 * single rolled-up "capturing: yes" would erase exactly the gap an operator needs.
 */
@Entity
@Table(name = "message_capture_node")
@IdClass(MessageCaptureNodeEntity.Key.class)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class MessageCaptureNodeEntity {

    @Id
    @Column(name = "subscription_id", nullable = false, updatable = false)
    private UUID subscriptionId;

    @Id
    @Column(name = "node_id", nullable = false, updatable = false)
    private UUID nodeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "capture_state", nullable = false)
    private CaptureState captureState = CaptureState.PENDING;

    /** Why it is in that state, in the words the UI shows. Null while ACTIVE. */
    @Column(name = "capture_detail")
    private String captureDetail;

    /**
     * When this node started being captured. Null while it never has been, and reset
     * on every fresh install, because a re-install after a failover does not cover the
     * interval it was down for.
     */
    @Column(name = "captured_from")
    private Instant capturedFrom;

    /** Messages the broker or Studio dropped, from the enqueue delta minus rows written. */
    @Column(name = "dropped_estimate", nullable = false)
    private long droppedEstimate;

    /** Payload bytes this node's capture holds in Postgres, against the subscription's cap. */
    @Column(name = "held_bytes", nullable = false)
    private long heldBytes;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Getter
    @Setter
    @NoArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private UUID subscriptionId;
        private UUID nodeId;
    }
}
