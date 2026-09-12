package io.github.sudoitir.artemisstudio.persist;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BrokerConfigNodeStateRepository
        extends JpaRepository<BrokerConfigNodeStateEntity, BrokerConfigNodeStateEntity.Key> {

    List<BrokerConfigNodeStateEntity> findByClusterId(UUID clusterId);

    void deleteByClusterId(UUID clusterId);
}
