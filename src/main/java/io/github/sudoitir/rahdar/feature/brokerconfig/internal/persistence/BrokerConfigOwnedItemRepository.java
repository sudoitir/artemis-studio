package io.github.sudoitir.rahdar.feature.brokerconfig.internal.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BrokerConfigOwnedItemRepository
        extends JpaRepository<BrokerConfigOwnedItemEntity, BrokerConfigOwnedItemEntity.Key> {

    List<BrokerConfigOwnedItemEntity> findByClusterId(UUID clusterId);
}
