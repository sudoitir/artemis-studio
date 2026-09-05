package io.github.sudoitir.artemisstudio.persist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Maps {@code api_token} (changeset 014). {@code tokenHash} is SHA-256 of the
 * generated secret; the plaintext is disclosed exactly once, at creation
 * (ADR-0039, design.md decision 5). Lookup is by the indexed {@code prefix}.
 */
@Entity
@Table(name = "api_token")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ApiTokenEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    @EqualsAndHashCode.Include
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "name", nullable = false, updatable = false)
    private String name;

    @Column(name = "prefix", nullable = false, updatable = false)
    private String prefix;

    @Column(name = "token_hash", nullable = false, updatable = false)
    private byte[] tokenHash;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public ApiTokenEntity(UUID userId, String name, String prefix, byte[] tokenHash, Instant expiresAt) {
        this.userId = userId;
        this.name = name;
        this.prefix = prefix;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public boolean isActive(Instant now) {
        return revokedAt == null && (expiresAt == null || expiresAt.isAfter(now));
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
