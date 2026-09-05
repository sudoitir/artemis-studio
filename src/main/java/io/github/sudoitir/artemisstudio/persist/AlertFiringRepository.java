package io.github.sudoitir.artemisstudio.persist;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AlertFiringRepository extends JpaRepository<AlertFiringEntity, Long> {

    List<AlertFiringEntity> findByClusterIdAndResolvedAtIsNullOrderByStartedAtDesc(UUID clusterId);

    Page<AlertFiringEntity> findByClusterIdOrderBySeqDesc(UUID clusterId, Pageable pageable);

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
