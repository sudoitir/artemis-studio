package io.github.sudoitir.rahdar.platform.governance.internal.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClassificationFindingRepository extends JpaRepository<ClassificationFindingEntity, UUID> {

    List<ClassificationFindingEntity> findByStatusOrderByLastSeenAtDesc(String status);

    List<ClassificationFindingEntity> findAllByOrderByLastSeenAtDesc();
}
