package io.github.sudoitir.artemisstudio.feature.alerting.internal.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AlertFiringRepository extends JpaRepository<AlertFiringEntity, Long> {

    /** A cluster's open firings, and the installation's too when {@code installation} is set. */
    @Query("SELECT f FROM AlertFiringEntity f WHERE f.resolvedAt IS NULL AND (f.clusterId = :clusterId "
            + "OR (:installation = true AND f.clusterId IS NULL)) ORDER BY f.startedAt DESC")
    List<AlertFiringEntity> findOpenVisible(
            @Param("clusterId") UUID clusterId, @Param("installation") boolean installation);

    @Query("SELECT f FROM AlertFiringEntity f WHERE f.clusterId = :clusterId "
            + "OR (:installation = true AND f.clusterId IS NULL) ORDER BY f.seq DESC")
    Page<AlertFiringEntity> findVisible(
            @Param("clusterId") UUID clusterId, @Param("installation") boolean installation, Pageable pageable);

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
