package io.github.sudoitir.artemisstudio.feature.plugins.internal.persistence;

import io.github.sudoitir.artemisstudio.feature.plugins.work.WorkState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Maps {@code plugin_owner_work} (feature/plugins changeset 0003). */
@Entity
@Table(name = "plugin_owner_work")
@Getter
@Setter
@NoArgsConstructor
public class OwnerWorkEntity {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "plugin_id", nullable = false, updatable = false)
    private String pluginId;

    @Column(name = "work_key", nullable = false, updatable = false)
    private String key;

    @Column(name = "owner_user_id", nullable = false)
    private UUID ownerUserId;

    /** The needs as a JSON list; read and written only by the owner-work service. */
    @Column(nullable = false)
    private String needs;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WorkState state;

    @Column
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
