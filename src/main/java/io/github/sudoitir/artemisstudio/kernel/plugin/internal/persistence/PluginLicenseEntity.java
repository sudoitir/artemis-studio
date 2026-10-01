package io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Maps {@code plugin_license} (changeset kernel-plugin-0010): a plugin's license file, as opaque
 * bytes, and the verdict the plugin reported for it. The verdict columns are empty until the plugin
 * reports on this exact file.
 */
@Entity
@Table(name = "plugin_license")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PluginLicenseEntity {

    @Id
    @Column(name = "plugin_id", nullable = false, updatable = false)
    private String pluginId;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    @Column(name = "reported_at")
    private Instant reportedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "size", nullable = false)
    private int size;

    @Column(name = "content", nullable = false)
    private byte[] content;

    @Column(name = "sha256", nullable = false)
    private String sha256;

    @Column(name = "uploaded_by", nullable = false)
    private String uploadedBy;

    @Column(name = "status")
    private String status;

    @Column(name = "licensee")
    private String licensee;

    @Column(name = "detail")
    private String detail;

    public PluginLicenseEntity(String pluginId) {
        this.pluginId = pluginId;
    }
}
