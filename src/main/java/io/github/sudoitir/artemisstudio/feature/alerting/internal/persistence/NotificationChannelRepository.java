package io.github.sudoitir.artemisstudio.feature.alerting.internal.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationChannelRepository extends JpaRepository<NotificationChannelEntity, UUID> {

    List<NotificationChannelEntity> findAllByOrderByName();
}
