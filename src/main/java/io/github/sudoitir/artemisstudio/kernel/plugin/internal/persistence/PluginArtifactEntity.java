package io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Maps {@code plugin_artifact} (changeset kernel-plugin-0001): a plugin jar's bytes,
 * content-addressed by sha256. {@code content} is not marked lazy — Hibernate only honors
 * {@code @Basic(fetch = LAZY)} with build-time bytecode enhancement, which this project does not
 * configure, so that annotation would be a silent no-op. A listing must never pull the blob; that
 * is enforced instead by {@link PluginArtifactRepository}'s JPQL projections ({@code
 * findAllSha256}, {@code findUnreferencedSha256}), which select only {@code sha256} and never load
 * this entity at all.
 */
@Entity
@Table(name = "plugin_artifact")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PluginArtifactEntity {

    @Id
    @Column(name = "sha256", nullable = false, updatable = false)
    private String sha256;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "content", nullable = false, updatable = false)
    private byte[] content;

    public PluginArtifactEntity(String sha256, byte[] content) {
        this.sha256 = sha256;
        this.content = content;
        this.sizeBytes = content.length;
    }

    @PrePersist
    void onInsert() {
        uploadedAt = Instant.now();
    }
}
