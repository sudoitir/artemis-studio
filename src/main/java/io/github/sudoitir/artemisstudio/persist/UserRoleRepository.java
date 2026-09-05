package io.github.sudoitir.artemisstudio.persist;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRoleRepository extends JpaRepository<UserRoleEntity, UserRoleEntity.Key> {

    List<UserRoleEntity> findByIdUserId(UUID userId);

    List<UserRoleEntity> findByIdRoleId(UUID roleId);

    long countByIdRoleId(UUID roleId);

    void deleteByIdScopeTypeAndIdScopeId(String scopeType, UUID scopeId);
}
