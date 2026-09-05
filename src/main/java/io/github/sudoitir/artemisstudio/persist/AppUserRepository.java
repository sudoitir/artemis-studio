package io.github.sudoitir.artemisstudio.persist;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUserEntity, UUID> {

    Optional<AppUserEntity> findByUsername(String username);

    Optional<AppUserEntity> findByIssuerAndSubject(String issuer, String subject);

    List<AppUserEntity> findAllByOrderByUsername();
}
