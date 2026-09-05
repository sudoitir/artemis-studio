package io.github.sudoitir.artemisstudio.persist;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertRuleChannelRepository extends JpaRepository<AlertRuleChannelEntity, AlertRuleChannelEntity.Key> {

    List<AlertRuleChannelEntity> findByRuleId(UUID ruleId);

    void deleteByRuleId(UUID ruleId);
}
