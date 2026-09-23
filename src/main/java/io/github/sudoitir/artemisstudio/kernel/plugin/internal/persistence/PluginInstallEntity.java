package io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginInstallStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps {@code plugin_install} (changeset kernel-plugin-0002): one row per installed plugin id,
 * the state machine {@link io.github.sudoitir.artemisstudio.kernel.plugin.internal.store.PluginStore}
 * drives.
 */
@Entity
@Table(name = "plugin_install")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PluginInstallEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private String id;

    @Column(name = "installed_at", nullable = false, updatable = false)
    private Instant installedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "version", nullable = false)
    private String version;

    @Column(name = "vendor", nullable = false)
    private String vendor;

    @Column(name = "sha256", nullable = false)
    private String sha256;

    @Column(name = "previous_sha256")
    private String previousSha256;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "failure")
    private String failure;

    @Column(name = "installed_by")
    private String installedBy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "descriptor", nullable = false)
    private String descriptor;

    @Column(name = "schema_changed", nullable = false)
    private boolean schemaChanged;

    public PluginInstallEntity(
            String id, String version, String vendor, String sha256, String installedBy, String descriptor) {
        this.id = id;
        this.version = version;
        this.vendor = vendor;
        this.sha256 = sha256;
        this.installedBy = installedBy;
        this.descriptor = descriptor;
        this.status = PluginInstallStatus.ACTIVATING.dbValue();
    }

    public PluginInstallStatus status() {
        return PluginInstallStatus.fromDbValue(status);
    }

    public void transitionTo(PluginInstallStatus next) {
        this.status = next.dbValue();
        if (next == PluginInstallStatus.ACTIVE) {
            this.activatedAt = Instant.now();
            this.failure = null;
        }
    }

    public void fail(String reason) {
        this.status = PluginInstallStatus.FAILED.dbValue();
        this.failure = reason;
    }

    /** Records a completed update: the current version becomes previous, the new one current. */
    public void update(String version, String sha256, String descriptor, boolean schemaChanged) {
        this.previousSha256 = this.sha256;
        this.version = version;
        this.sha256 = sha256;
        this.descriptor = descriptor;
        this.schemaChanged = schemaChanged;
        this.status = PluginInstallStatus.ACTIVATING.dbValue();
    }

    @PrePersist
    void onInsert() {
        installedAt = Instant.now();
        updatedAt = installedAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
