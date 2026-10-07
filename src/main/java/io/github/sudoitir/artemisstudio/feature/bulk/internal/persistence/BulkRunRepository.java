package io.github.sudoitir.artemisstudio.feature.bulk.internal.persistence;

import io.github.sudoitir.artemisstudio.feature.bulk.BulkRunStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
    Page<BulkRunEntity> findByClusterIdAndStatusNotOrderByCreatedAtDesc(
            UUID clusterId, BulkRunStatus status, Pageable page);

    /**
     * Move a preview to executing, once: zero when it was not a preview any more. Fails on the partial
     * unique index when another run is executing on the cluster.
     */
    @Modifying
    @Transactional
    @Query("update BulkRunEntity r set r.status = :to where r.id = :id and r.status = :from")
    int transition(@Param("id") UUID id, @Param("from") BulkRunStatus from, @Param("to") BulkRunStatus to);

    /**
     * Keep a preview that is held for approval until {@code to}, so it is still there when the hold is approved:
     * zero when it is not a preview any more or already lasts that long.
     */
    @Modifying
    @Transactional
    @Query("update BulkRunEntity r set r.expiresAt = :to"
            + " where r.id = :id and r.status = io.github.sudoitir.artemisstudio.feature.bulk.BulkRunStatus.PREVIEWED"
            + " and r.expiresAt < :to")
    int extendPreview(@Param("id") UUID id, @Param("to") Instant to);

    /**
     * Record that the operator asked to stop a run that is executing, once: zero when it is not executing
     * or a stop was already asked. The executing replica reads it, whether or not the signal reaches it.
     */
    @Modifying
    @Transactional
    @Query("update BulkRunEntity r set r.stopRequestedAt = :now"
            + " where r.id = :id and r.status = io.github.sudoitir.artemisstudio.feature.bulk.BulkRunStatus.RUNNING"
            + " and r.stopRequestedAt is null")
    int requestStop(@Param("id") UUID id, @Param("now") Instant now);

    boolean existsByIdAndStopRequestedAtIsNotNull(UUID id);

    /**
     * Take the row lock of a run that is still executing on {@code replica}: one when it is, zero when
     * recovery or another replica has taken it over. Called at the start of the runner's own transaction,
     * so what it writes next commits before anything else can change the run, and never after.
     */
    @Modifying
    @Query("update BulkRunEntity r set r.status = r.status"
            + " where r.id = :id and r.status = io.github.sudoitir.artemisstudio.feature.bulk.BulkRunStatus.RUNNING"
            + " and r.replicaId = :replica")
    int fence(@Param("id") UUID id, @Param("replica") UUID replica);
}
