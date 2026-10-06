package io.github.sudoitir.artemisstudio.kernel.security.internal.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamRepository extends JpaRepository<TeamEntity, UUID> {

    List<TeamEntity> findAllByOrderByName();

    boolean existsByNameIgnoreCase(String name);
}
