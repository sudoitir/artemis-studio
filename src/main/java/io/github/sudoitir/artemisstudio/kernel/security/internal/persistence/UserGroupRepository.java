package io.github.sudoitir.artemisstudio.kernel.security.internal.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserGroupRepository extends JpaRepository<UserGroupEntity, UserGroupEntity.Key> {

    List<UserGroupEntity> findByIdUserId(UUID userId);
}
