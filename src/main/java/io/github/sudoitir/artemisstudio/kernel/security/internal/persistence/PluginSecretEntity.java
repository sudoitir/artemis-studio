package io.github.sudoitir.artemisstudio.kernel.security.internal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Maps {@code plugin_secret} (kernel/security changeset 0002): one sealed value per plugin and name. */
@Entity
@Table(name = "plugin_secret")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PluginSecretEntity {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "plugin_id", nullable = false, updatable = false)
    private String pluginId;

    @Column(nullable = false, updatable = false)
    private String name;

    @Column(nullable = false)
    private byte[] ciphertext;

    @Column(nullable = false)
    private byte[] nonce;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public PluginSecretEntity(String pluginId, String name) {
        this.pluginId = pluginId;
        this.name = name;
    }
}
