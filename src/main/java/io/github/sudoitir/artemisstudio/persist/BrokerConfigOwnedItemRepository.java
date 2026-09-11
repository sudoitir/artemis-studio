package io.github.sudoitir.artemisstudio.persist;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BrokerConfigOwnedItemRepository
        extends JpaRepository<BrokerConfigOwnedItemEntity, BrokerConfigOwnedItemEntity.Key> {

    List<BrokerConfigOwnedItemEntity> findByClusterId(UUID clusterId);
}
