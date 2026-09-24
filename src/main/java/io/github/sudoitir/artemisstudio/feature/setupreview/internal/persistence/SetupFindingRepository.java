package io.github.sudoitir.artemisstudio.feature.setupreview.internal.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SetupFindingRepository extends JpaRepository<SetupFindingEntity, FindingKey> {

    List<SetupFindingEntity> findByClusterId(UUID clusterId);
}
