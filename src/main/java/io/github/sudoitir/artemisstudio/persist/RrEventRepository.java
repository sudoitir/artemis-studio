package io.github.sudoitir.artemisstudio.persist;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RrEventRepository extends JpaRepository<RrEventEntity, Long> {

    List<RrEventEntity> findByFlowIdOrderByTsAsc(UUID flowId);
}
