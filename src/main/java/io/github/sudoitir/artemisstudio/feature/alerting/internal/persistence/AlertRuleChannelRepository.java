package io.github.sudoitir.artemisstudio.feature.alerting.internal.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AlertRuleChannelRepository extends JpaRepository<AlertRuleChannelEntity, AlertRuleChannelEntity.Key> {

    List<AlertRuleChannelEntity> findByRuleId(UUID ruleId);

    void deleteByRuleId(UUID ruleId);

    /** How many rules route to each channel — what deleting one would silence. */
    @Query("SELECT rc.channelId AS channelId, count(rc) AS rules FROM AlertRuleChannelEntity rc GROUP BY rc.channelId")
    List<BoundRules> boundRuleCounts();

    long countByChannelId(UUID channelId);

    interface BoundRules {
        UUID getChannelId();

        long getRules();
    }
}
