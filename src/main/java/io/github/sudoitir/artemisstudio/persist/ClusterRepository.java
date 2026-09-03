package io.github.sudoitir.artemisstudio.persist;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** {@code cluster} access (ADR-0011: JPA over the Liquibase-owned schema). */
public interface ClusterRepository extends JpaRepository<ClusterEntity, UUID> {

    List<ClusterEntity> findAllByOrderByNameAsc();
}
