package io.github.sudoitir.artemisstudio.feature.plugins.internal.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface OwnerWorkRepository extends JpaRepository<OwnerWorkEntity, UUID> {

    Optional<OwnerWorkEntity> findByPluginIdAndKey(String pluginId, String key);

    List<OwnerWorkEntity> findByPluginIdOrderByKey(String pluginId);

    @Transactional
    long deleteByPluginId(String pluginId);
}
