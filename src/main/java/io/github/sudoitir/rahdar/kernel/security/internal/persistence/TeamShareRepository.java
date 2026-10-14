package io.github.sudoitir.rahdar.kernel.security.internal.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamShareRepository extends JpaRepository<TeamShareEntity, UUID> {

    List<TeamShareEntity> findByOwnerTeamId(UUID teamId);

    List<TeamShareEntity> findByTargetTeamId(UUID teamId);

    Optional<TeamShareEntity> findByIdAndOwnerTeamId(UUID id, UUID ownerTeamId);

    boolean existsByRoleId(UUID roleId);

    void deleteByClusterId(UUID clusterId);
}
