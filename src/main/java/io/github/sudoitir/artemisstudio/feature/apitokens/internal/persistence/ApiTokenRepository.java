package io.github.sudoitir.artemisstudio.feature.apitokens.internal.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiTokenRepository extends JpaRepository<ApiTokenEntity, UUID> {

    Optional<ApiTokenEntity> findByPrefix(String prefix);

    List<ApiTokenEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
