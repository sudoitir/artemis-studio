package io.github.sudoitir.artemisstudio.persist;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RrExpectationRepository extends JpaRepository<RrExpectationEntity, UUID> {

    List<RrExpectationEntity> findByClusterIdOrderByRequestAddress(UUID clusterId);

    List<RrExpectationEntity> findByEnabledTrue();
}
