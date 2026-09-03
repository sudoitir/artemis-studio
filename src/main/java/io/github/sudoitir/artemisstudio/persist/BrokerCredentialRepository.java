package io.github.sudoitir.artemisstudio.persist;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** {@code broker_credential} access (ADR-0011). */
public interface BrokerCredentialRepository extends JpaRepository<BrokerCredentialEntity, UUID> {

    Optional<BrokerCredentialEntity> findByClusterIdAndKind(UUID clusterId, String kind);
}
