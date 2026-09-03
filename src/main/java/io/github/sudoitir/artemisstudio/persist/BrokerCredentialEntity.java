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

/**
 * Maps the {@code broker_credential} table (changeset 002). The secret is only
 * ever held here as AES-GCM ciphertext plus its nonce (ADR-0009); the key lives
 * in the environment and is never persisted.
 */
@Entity
@Table(name = "broker_credential")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class BrokerCredentialEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    @EqualsAndHashCode.Include
    private UUID id;

    @Column(name = "cluster_id", nullable = false, updatable = false)
    private UUID clusterId;

    @Column(name = "kind", nullable = false)
    private String kind;

    @Column(name = "username")
    private String username;

    @Column(name = "secret_ct", nullable = false)
    private byte[] secretCt;

    @Column(name = "secret_nonce", nullable = false)
    private byte[] secretNonce;

    public BrokerCredentialEntity(UUID clusterId, String kind, String username, byte[] secretCt, byte[] secretNonce) {
        this.clusterId = clusterId;
        this.kind = kind;
        this.username = username;
        this.secretCt = secretCt;
        this.secretNonce = secretNonce;
    }

    public void replaceSecret(String username, byte[] secretCt, byte[] secretNonce) {
        this.username = username;
        this.secretCt = secretCt;
        this.secretNonce = secretNonce;
    }
}
