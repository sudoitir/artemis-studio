package io.github.sudoitir.artemisstudio.feature.apitokens.internal.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiTokenGrantRepository extends JpaRepository<ApiTokenGrantEntity, ApiTokenGrantEntity.Key> {

    List<ApiTokenGrantEntity> findByIdTokenId(UUID tokenId);

    void deleteByIdScopeTypeAndIdScopeId(String scopeType, UUID scopeId);

    /** Purge (design.md §4): every grant a plugin's permissions contributed, keyed by its {@code <id>:} prefix. */
    void deleteByIdActionStartingWith(String actionPrefix);
}
