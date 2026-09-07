package io.github.sudoitir.artemisstudio.persist;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageIndexSubscriptionRepository extends JpaRepository<MessageIndexSubscriptionEntity, UUID> {

    List<MessageIndexSubscriptionEntity> findByClusterId(UUID clusterId);

    List<MessageIndexSubscriptionEntity> findByEnabledTrue();
}
