package io.github.sudoitir.artemisstudio.kernel.security.internal.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface PluginSecretRepository extends JpaRepository<PluginSecretEntity, UUID> {

    Optional<PluginSecretEntity> findByPluginIdAndName(String pluginId, String name);

    List<PluginSecretEntity> findByPluginIdOrderByName(String pluginId);

    @Transactional
    long deleteByPluginIdAndName(String pluginId, String name);

    @Transactional
    long deleteByPluginId(String pluginId);
}
