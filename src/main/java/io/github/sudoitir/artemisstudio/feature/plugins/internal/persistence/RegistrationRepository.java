package io.github.sudoitir.artemisstudio.feature.plugins.internal.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface RegistrationRepository extends JpaRepository<RegistrationEntity, UUID> {

    Optional<RegistrationEntity> findByPluginIdAndKey(String pluginId, String key);

    List<RegistrationEntity> findByPluginIdOrderByKey(String pluginId);

    List<RegistrationEntity> findByClusterId(UUID clusterId);

    @Transactional
    long deleteByPluginId(String pluginId);
}
