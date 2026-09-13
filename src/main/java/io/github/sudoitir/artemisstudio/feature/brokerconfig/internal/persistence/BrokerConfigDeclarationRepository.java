package io.github.sudoitir.artemisstudio.feature.brokerconfig.internal.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BrokerConfigDeclarationRepository extends JpaRepository<BrokerConfigDeclarationEntity, UUID> {}
