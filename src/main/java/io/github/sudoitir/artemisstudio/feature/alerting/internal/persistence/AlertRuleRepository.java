package io.github.sudoitir.artemisstudio.feature.alerting.internal.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AlertRuleRepository extends JpaRepository<AlertRuleEntity, UUID> {

    List<AlertRuleEntity> findByClusterIdAndKindAndEnabledTrue(UUID clusterId, String kind);

    /** The installation-scoped rules (ADR-0135): those with no cluster. */
    List<AlertRuleEntity> findByClusterIdIsNullAndKindAndEnabledTrue(String kind);

    List<AlertRuleEntity> findByClusterId(UUID clusterId);

    /** A cluster's rules, and the installation's too when {@code installation} is set. */
    @Query("SELECT r FROM AlertRuleEntity r WHERE r.clusterId = :clusterId "
            + "OR (:installation = true AND r.clusterId IS NULL) ORDER BY r.name")
    List<AlertRuleEntity> findVisible(@Param("clusterId") UUID clusterId, @Param("installation") boolean installation);
}
