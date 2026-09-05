package io.github.sudoitir.artemisstudio.persist;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RolePermissionRepository extends JpaRepository<RolePermissionEntity, RolePermissionEntity.Key> {

    List<RolePermissionEntity> findByIdRoleId(UUID roleId);

    void deleteByIdRoleId(UUID roleId);
}
