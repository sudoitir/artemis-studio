package io.github.sudoitir.artemisstudio.feature.bulk.internal.persistence;

import io.github.sudoitir.artemisstudio.feature.bulk.BulkRunStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** {@code bulk_run} access (ADR-0011). */
public interface BulkRunRepository extends JpaRepository<BulkRunEntity, UUID> {

    Optional<BulkRunEntity> findByIdAndClusterId(UUID id, UUID clusterId);

    Optional<BulkRunEntity> findFirstByClusterIdAndStatus(UUID clusterId, BulkRunStatus status);

    List<BulkRunEntity> findByStatus(BulkRunStatus status);

    /** Executed runs, newest first: the history. */
    List<BulkRunEntity> findByClusterIdAndStatusNotOrderByCreatedAtDesc(
            UUID clusterId, BulkRunStatus status, Limit limit);

    /**
     * Move a preview to executing, once: zero when it was not a preview any more. Fails on the partial
     * unique index when another run is executing on the cluster.
     */
    @Modifying
    @Transactional
    @Query("update BulkRunEntity r set r.status = :to where r.id = :id and r.status = :from")
    int transition(@Param("id") UUID id, @Param("from") BulkRunStatus from, @Param("to") BulkRunStatus to);

    @Modifying
    @Transactional
    @Query("delete from BulkRunEntity r where r.status = :status and r.expiresAt < :now")
    int deleteExpired(@Param("status") BulkRunStatus status, @Param("now") Instant now);
}
