package io.github.sudoitir.artemisstudio.persist;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OidcRoleMappingRepository extends JpaRepository<OidcRoleMappingEntity, UUID> {

    List<OidcRoleMappingEntity> findByClaim(String claim);

    List<OidcRoleMappingEntity> findAllByOrderByClaimAscClaimValueAsc();
}
