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

/** Maps {@code plugin_trusted_key} (changeset kernel-plugin-0008): one pinned publisher key. */
@Entity
@Table(name = "plugin_trusted_key")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TrustedKeyEntity {

    @Column(name = "added_at", nullable = false, updatable = false)
    private Instant addedAt;

    @Id
    @Column(name = "fingerprint", nullable = false, updatable = false)
    private String fingerprint;

    @Column(name = "name", nullable = false, updatable = false)
    private String name;

    @Column(name = "subject", nullable = false, updatable = false)
    private String subject;

    @Column(name = "public_key", nullable = false, updatable = false)
    private byte[] publicKey;

    @Column(name = "added_by", nullable = false, updatable = false)
    private String addedBy;

    public TrustedKeyEntity(String fingerprint, String name, String subject, byte[] publicKey, String addedBy) {
        this.fingerprint = fingerprint;
        this.name = name;
        this.subject = subject;
        this.publicKey = publicKey;
        this.addedBy = addedBy;
    }

    @PrePersist
    void onInsert() {
        addedAt = Instant.now();
    }
}
