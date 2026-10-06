package io.github.sudoitir.artemisstudio.kernel.security.internal.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamShareRepository extends JpaRepository<TeamShareEntity, UUID> {

    void deleteByClusterId(UUID clusterId);
}
