package io.github.sudoitir.artemisstudio.feature.alerting.internal.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AlertFiringRepository extends JpaRepository<AlertFiringEntity, Long> {

    /**
     * A cluster's open firings, and the installation's too when {@code installation} is set. Unless
     * {@code everything} is set, only those of the rules in {@code ruleIds}.
     */
    @Query("SELECT f FROM AlertFiringEntity f WHERE f.resolvedAt IS NULL AND ((f.clusterId = :clusterId "
            + "AND (:everything = true OR f.ruleId IN :ruleIds)) "
            + "OR (:installation = true AND f.clusterId IS NULL)) ORDER BY f.startedAt DESC")
    List<AlertFiringEntity> findOpenVisible(
            @Param("clusterId") UUID clusterId,
            @Param("installation") boolean installation,
            @Param("everything") boolean everything,
            @Param("ruleIds") Collection<UUID> ruleIds);

    @Query("SELECT f FROM AlertFiringEntity f WHERE (f.clusterId = :clusterId "
            + "AND (:everything = true OR f.ruleId IN :ruleIds)) "
            + "OR (:installation = true AND f.clusterId IS NULL) ORDER BY f.seq DESC")
    Page<AlertFiringEntity> findVisible(
            @Param("clusterId") UUID clusterId,
            @Param("installation") boolean installation,
            @Param("everything") boolean everything,
            @Param("ruleIds") Collection<UUID> ruleIds,
            Pageable pageable);

    long countByClusterIdAndResolvedAtIsNullAndRuleIdIn(UUID clusterId, Collection<UUID> ruleIds);

    Optional<AlertFiringEntity> findFirstByRuleIdAndSubjectKeyAndResolvedAtIsNull(UUID ruleId, String subjectKey);

    long countByClusterIdAndResolvedAtIsNull(UUID clusterId);

    @Query("SELECT f.clusterId AS clusterId, COUNT(f) AS firing FROM AlertFiringEntity f "
            + "WHERE f.resolvedAt IS NULL GROUP BY f.clusterId")
    List<ClusterFiringCount> firingCountsByCluster();

    interface ClusterFiringCount {
        UUID getClusterId();

        long getFiring();
    }
}
