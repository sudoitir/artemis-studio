package io.github.sudoitir.rahdar.kernel.security.internal.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamPatternRepository extends JpaRepository<TeamPatternEntity, UUID> {

    List<TeamPatternEntity> findByTeamId(UUID teamId);

    Optional<TeamPatternEntity> findByIdAndTeamId(UUID id, UUID teamId);

    List<TeamPatternEntity> findByClusterId(UUID clusterId);

    void deleteByClusterId(UUID clusterId);
}
