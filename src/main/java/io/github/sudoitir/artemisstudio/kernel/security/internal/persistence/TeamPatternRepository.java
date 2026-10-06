package io.github.sudoitir.artemisstudio.kernel.security.internal.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamPatternRepository extends JpaRepository<TeamPatternEntity, UUID> {

    void deleteByClusterId(UUID clusterId);
}
