package io.github.sudoitir.artemisstudio.persist;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BrokerConfigRevisionRepository extends JpaRepository<BrokerConfigRevisionEntity, Long> {

    List<BrokerConfigRevisionEntity> findByClusterIdOrderByRevisionDesc(UUID clusterId);

    Optional<BrokerConfigRevisionEntity> findByClusterIdAndRevision(UUID clusterId, int revision);

    Optional<BrokerConfigRevisionEntity> findFirstByClusterIdOrderByRevisionDesc(UUID clusterId);
}
