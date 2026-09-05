package io.github.sudoitir.artemisstudio.persist;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EnvironmentRepository extends JpaRepository<EnvironmentEntity, UUID> {

    List<EnvironmentEntity> findAllByOrderBySortOrderAscNameAsc();

    boolean existsByName(String name);
}
