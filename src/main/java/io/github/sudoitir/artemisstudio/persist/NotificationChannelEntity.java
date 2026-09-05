package io.github.sudoitir.artemisstudio.persist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps {@code notification_channel} (changesets 006, 013). Global, not
 * per-cluster (design.md decision 6) — routing is expressed by which rules bind
 * to a channel via {@link AlertRuleChannelEntity}. The secret (a Slack webhook
 * URL, or a webhook signing secret) is AES-GCM ciphertext in
 * {@code secretCt}/{@code secretNonce} (ADR-0009 via ADR-0036's opaque-AAD
 * overload); {@code config} holds only non-secret parts.
 */
@Entity
@Table(name = "notification_channel")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class NotificationChannelEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    @EqualsAndHashCode.Include
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "kind", nullable = false, updatable = false)
    private String kind;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config", nullable = false)
    private String config = "{}";

    @Column(name = "secret_ct")
    private byte[] secretCt;

    @Column(name = "secret_nonce")
    private byte[] secretNonce;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    public NotificationChannelEntity(String name, String kind, String config, byte[] secretCt, byte[] secretNonce) {
        this.name = name;
        this.kind = kind;
        this.config = config != null ? config : "{}";
        this.secretCt = secretCt;
        this.secretNonce = secretNonce;
        this.enabled = true;
    }

    public void replaceSecret(byte[] secretCt, byte[] secretNonce) {
        this.secretCt = secretCt;
        this.secretNonce = secretNonce;
    }
}
