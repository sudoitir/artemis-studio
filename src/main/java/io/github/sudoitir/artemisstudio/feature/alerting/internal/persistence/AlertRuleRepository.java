package io.github.sudoitir.artemisstudio.feature.alerting.internal.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertRuleRepository extends JpaRepository<AlertRuleEntity, UUID> {

    List<AlertRuleEntity> findByClusterIdAndKindAndEnabledTrue(UUID clusterId, String kind);

    List<AlertRuleEntity> findByClusterIdOrderByName(UUID clusterId);
}
