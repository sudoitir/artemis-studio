package io.github.sudoitir.artemisstudio.kernel.security.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUserEntity, UUID> {

    Optional<AppUserEntity> findByUsername(String username);

    Optional<AppUserEntity> findByProviderIdAndExternalSubject(String providerId, String externalSubject);

    List<AppUserEntity> findAllByOrderByUsername();
}
