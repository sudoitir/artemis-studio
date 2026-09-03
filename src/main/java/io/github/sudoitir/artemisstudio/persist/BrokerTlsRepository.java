package io.github.sudoitir.artemisstudio.persist;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** {@code broker_tls} access (ADR-0011). */
public interface BrokerTlsRepository extends JpaRepository<BrokerTlsEntity, UUID> {

    Optional<BrokerTlsEntity> findByClusterId(UUID clusterId);
}
