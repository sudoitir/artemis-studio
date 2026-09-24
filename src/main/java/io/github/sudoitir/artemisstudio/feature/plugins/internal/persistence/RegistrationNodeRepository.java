package io.github.sudoitir.artemisstudio.feature.plugins.internal.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RegistrationNodeRepository extends JpaRepository<RegistrationNodeEntity, RegistrationNodeEntity.Key> {

    List<RegistrationNodeEntity> findByIdRegistrationId(UUID registrationId);
}
