package io.github.sudoitir.artemisstudio.kernel.security.internal.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamPatternRepository extends JpaRepository<TeamPatternEntity, UUID> {

    java.util.List<TeamPatternEntity> findByTeamId(UUID teamId);

    java.util.Optional<TeamPatternEntity> findByIdAndTeamId(UUID id, UUID teamId);

    java.util.List<TeamPatternEntity> findByClusterId(UUID clusterId);

    void deleteByClusterId(UUID clusterId);
}
