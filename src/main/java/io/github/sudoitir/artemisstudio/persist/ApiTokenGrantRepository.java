package io.github.sudoitir.artemisstudio.persist;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiTokenGrantRepository extends JpaRepository<ApiTokenGrantEntity, ApiTokenGrantEntity.Key> {

    List<ApiTokenGrantEntity> findByIdTokenId(UUID tokenId);

    void deleteByIdScopeTypeAndIdScopeId(String scopeType, UUID scopeId);
}
