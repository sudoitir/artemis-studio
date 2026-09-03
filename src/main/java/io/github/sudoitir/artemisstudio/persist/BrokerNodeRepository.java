package io.github.sudoitir.artemisstudio.persist;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@code broker_node} access (ADR-0011). Updates are dirty-checking mutations on
 * a managed {@link BrokerNodeEntity} — no delete+reinsert, so row identity and
 * every {@code audit_event.node_id} reference are stable across refreshes.
 */
public interface BrokerNodeRepository extends JpaRepository<BrokerNodeEntity, UUID> {

    List<BrokerNodeEntity> findByClusterIdOrderByNameAsc(UUID clusterId);

    Optional<BrokerNodeEntity> findByClusterIdAndName(UUID clusterId, String name);
}
