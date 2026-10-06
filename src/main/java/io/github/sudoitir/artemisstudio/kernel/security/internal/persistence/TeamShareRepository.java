package io.github.sudoitir.artemisstudio.kernel.security.internal.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamShareRepository extends JpaRepository<TeamShareEntity, UUID> {

    java.util.List<TeamShareEntity> findByOwnerTeamId(UUID teamId);

    java.util.List<TeamShareEntity> findByTargetTeamId(UUID teamId);

    java.util.Optional<TeamShareEntity> findByIdAndOwnerTeamId(UUID id, UUID ownerTeamId);

    boolean existsByRoleId(UUID roleId);

    void deleteByClusterId(UUID clusterId);
}
