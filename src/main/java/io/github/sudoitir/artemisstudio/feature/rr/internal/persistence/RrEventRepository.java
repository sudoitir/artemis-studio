package io.github.sudoitir.artemisstudio.feature.rr.internal.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RrEventRepository extends JpaRepository<RrEventEntity, Long> {

    List<RrEventEntity> findByFlowIdOrderByTsAsc(UUID flowId);
}
