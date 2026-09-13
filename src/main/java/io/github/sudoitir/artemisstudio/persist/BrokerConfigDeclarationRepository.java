package io.github.sudoitir.artemisstudio.persist;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BrokerConfigDeclarationRepository extends JpaRepository<BrokerConfigDeclarationEntity, UUID> {}
