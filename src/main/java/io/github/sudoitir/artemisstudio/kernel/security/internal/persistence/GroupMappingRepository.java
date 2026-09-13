package io.github.sudoitir.artemisstudio.kernel.security.internal.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GroupMappingRepository extends JpaRepository<GroupMappingEntity, UUID> {

    List<GroupMappingEntity> findByProviderId(String providerId);

    List<GroupMappingEntity> findByProviderIdOrderByGroupNameAsc(String providerId);

    Optional<GroupMappingEntity> findByIdAndProviderId(UUID id, String providerId);

    void deleteByScopeTypeAndScopeId(String scopeType, UUID scopeId);
}
