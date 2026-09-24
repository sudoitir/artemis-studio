package io.github.sudoitir.artemisstudio.feature.plugins.internal.persistence;

import io.github.sudoitir.artemisstudio.feature.plugins.messaging.RegistrationState;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Maps {@code plugin_message_registration_node}: what the last pass found on one node. */
@Entity
@Table(name = "plugin_message_registration_node")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RegistrationNodeEntity {

    @EmbeddedId
    private Key id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RegistrationState state;

    private String detail;

    @Column(name = "dropped_copies")
    private Long droppedCopies;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public RegistrationNodeEntity(UUID registrationId, UUID nodeId) {
        this.id = new Key(registrationId, nodeId);
    }

    public UUID getNodeId() {
        return id.nodeId;
    }

    @Embeddable
    @Getter
    @EqualsAndHashCode
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    public static class Key implements Serializable {
        @Column(name = "registration_id")
        private UUID registrationId;

        @Column(name = "node_id")
        private UUID nodeId;

        Key(UUID registrationId, UUID nodeId) {
            this.registrationId = registrationId;
            this.nodeId = nodeId;
        }
    }
}
