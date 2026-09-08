package io.github.sudoitir.artemisstudio.persist;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageCaptureNodeRepository
        extends JpaRepository<MessageCaptureNodeEntity, MessageCaptureNodeEntity.Key> {

    List<MessageCaptureNodeEntity> findBySubscriptionId(UUID subscriptionId);

    Optional<MessageCaptureNodeEntity> findBySubscriptionIdAndNodeId(UUID subscriptionId, UUID nodeId);

    void deleteBySubscriptionIdAndNodeId(UUID subscriptionId, UUID nodeId);
}
