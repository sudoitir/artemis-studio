package io.github.sudoitir.artemisstudio.feature.setupreview.internal.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SetupFindingAcceptanceRepository extends JpaRepository<SetupFindingAcceptanceEntity, FindingKey> {

    List<SetupFindingAcceptanceEntity> findByClusterId(UUID clusterId);
}
