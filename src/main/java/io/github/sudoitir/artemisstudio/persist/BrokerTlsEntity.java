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
 * Maps the {@code broker_tls} table (changeset 002). {@code truststoreRef} and
 * {@code clientCertRef} are Spring SSL bundle names, not filesystem paths
 * (ADR-0009).
 */
@Entity
@Table(name = "broker_tls")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class BrokerTlsEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    @EqualsAndHashCode.Include
    private UUID id;

    @Column(name = "cluster_id", nullable = false, updatable = false)
    private UUID clusterId;

    @Column(name = "truststore_ref")
    private String truststoreRef;

    @Column(name = "client_cert_ref")
    private String clientCertRef;

    @Column(name = "verify_hostname", nullable = false)
    private boolean verifyHostname = true;

    public BrokerTlsEntity(UUID clusterId, String truststoreRef, String clientCertRef, boolean verifyHostname) {
        this.clusterId = clusterId;
        this.truststoreRef = truststoreRef;
        this.clientCertRef = clientCertRef;
        this.verifyHostname = verifyHostname;
    }

    public void update(String truststoreRef, String clientCertRef, boolean verifyHostname) {
        this.truststoreRef = truststoreRef;
        this.clientCertRef = clientCertRef;
        this.verifyHostname = verifyHostname;
    }
}
