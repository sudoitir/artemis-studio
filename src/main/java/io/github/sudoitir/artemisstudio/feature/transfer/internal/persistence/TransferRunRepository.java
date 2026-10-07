package io.github.sudoitir.artemisstudio.feature.transfer.internal.persistence;

import io.github.sudoitir.artemisstudio.feature.transfer.TransferState;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** {@code transfer_run} access (ADR-0011). */
public interface TransferRunRepository extends JpaRepository<TransferRunEntity, UUID> {

    List<TransferRunEntity> findByStateIn(Collection<TransferState> states);

    long countByStateIn(Collection<TransferState> states);

    boolean existsById(UUID id);

    /** Runs where the cluster is the source or the target, newest first, previews excluded. */
    @Query("select r from TransferRunEntity r where (r.sourceClusterId = :cluster or r.targetClusterId = :cluster)"
            + " and r.state <> io.github.sudoitir.artemisstudio.feature.transfer.TransferState.PREVIEWED"
            + " order by r.createdAt desc")
    List<TransferRunEntity> history(@Param("cluster") UUID clusterId, Limit limit);

    /**
     * Move a run from one of {@code from} to {@code to}, once: zero when it was in none of them any
     * more. Fails on the partial unique index when another run is active on the same source queue. A
     * stop asked of the previous segment is forgotten, so a resumed run is not stopped at once.
     */
    @Modifying
    @Transactional
    @Query("update TransferRunEntity r set r.state = :to, r.stopRequestedAt = null"
            + " where r.id = :id and r.state in :from")
    int transition(@Param("id") UUID id, @Param("from") Collection<TransferState> from, @Param("to") TransferState to);

    /**
     * Keep a preview that is held for approval until {@code to}, so it is still there when the hold is approved:
     * zero when it is not a preview any more or already lasts that long.
     */
    @Modifying
    @Transactional
    @Query("update TransferRunEntity r set r.expiresAt = :to"
            + " where r.id = :id and r.state = io.github.sudoitir.artemisstudio.feature.transfer.TransferState.PREVIEWED"
            + " and r.expiresAt < :to")
    int extendPreview(@Param("id") UUID id, @Param("to") Instant to);

    /**
     * Record that the operator asked to stop a run that is executing, once: zero when it is not executing
     * or a stop was already asked. The executing replica reads it, whether or not the signal reaches it.
     */
    @Modifying
    @Transactional
    @Query("update TransferRunEntity r set r.stopRequestedAt = :now where r.id = :id"
            + " and r.state in (io.github.sudoitir.artemisstudio.feature.transfer.TransferState.RUNNING,"
            + " io.github.sudoitir.artemisstudio.feature.transfer.TransferState.WAITING_FOR_CAPACITY,"
            + " io.github.sudoitir.artemisstudio.feature.transfer.TransferState.RETURNING)"
            + " and r.stopRequestedAt is null")
    int requestStop(@Param("id") UUID id, @Param("now") Instant now);

    boolean existsByIdAndStopRequestedAtIsNotNull(UUID id);

    /**
     * Take the row lock of a run that is still active on {@code replica}: one when it is, zero when
     * recovery or another replica has taken it over. Called at the start of the runner's own transaction,
     * so what it writes next commits before anything else can change the run, and never after.
     */
    @Modifying
    @Query("update TransferRunEntity r set r.state = r.state where r.id = :id"
            + " and r.state in (io.github.sudoitir.artemisstudio.feature.transfer.TransferState.RUNNING,"
            + " io.github.sudoitir.artemisstudio.feature.transfer.TransferState.WAITING_FOR_CAPACITY,"
            + " io.github.sudoitir.artemisstudio.feature.transfer.TransferState.RETURNING)"
            + " and r.replicaId = :replica")
    int fence(@Param("id") UUID id, @Param("replica") UUID replica);
}
