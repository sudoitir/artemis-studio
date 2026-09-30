package io.github.sudoitir.artemisstudio.feature.apitokens.internal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps {@code api_token}. {@code tokenHash} is SHA-256 of the generated secret; the
 * plaintext is disclosed exactly once, at creation or rotation (ADR-0039). Lookup is by
 * the indexed {@code prefix}, or {@code previousPrefix} for the secret a rotation replaced
 * (ADR-0136).
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

    @Column(name = "prefix", nullable = false)
    private String prefix;

    @Column(name = "token_hash", nullable = false)
    private byte[] tokenHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "previous_prefix")
    private String previousPrefix;

    @Column(name = "previous_token_hash")
    private byte[] previousTokenHash;

    @Column(name = "previous_valid_until")
    private Instant previousValidUntil;

    /** MCP tool names this token may call; empty means every tool its grants permit. */
    @Column(name = "mcp_tools", nullable = false, columnDefinition = "text[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> mcpTools = new ArrayList<>();

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public ApiTokenEntity(
            UUID userId, String name, String prefix, byte[] tokenHash, Instant expiresAt, List<String> mcpTools) {
        this.userId = userId;
        this.name = name;
        this.prefix = prefix;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.mcpTools = new ArrayList<>(mcpTools);
    }

    /** The earlier of the token's own expiry and its creation plus the current cap (ADR-0136). */
    public Instant effectiveExpiry(Duration cap) {
        Instant capped = createdAt.plus(cap);
        return capped.isBefore(expiresAt) ? capped : expiresAt;
    }

    public boolean isActive(Instant now, Duration cap) {
        return revokedAt == null && effectiveExpiry(cap).isAfter(now);
    }

    /** Moves the current secret to the previous slot, valid until {@code overlapEnds}, and installs a new one. */
    public void rotate(String newPrefix, byte[] newHash, Instant overlapEnds) {
        this.previousPrefix = prefix;
        this.previousTokenHash = tokenHash;
        this.previousValidUntil = overlapEnds;
        this.prefix = newPrefix;
        this.tokenHash = newHash;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
