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
     * more. Fails on the partial unique index when another run is active on the same source queue.
     */
    @Modifying
    @Transactional
    @Query("update TransferRunEntity r set r.state = :to where r.id = :id and r.state in :from")
    int transition(@Param("id") UUID id, @Param("from") Collection<TransferState> from, @Param("to") TransferState to);

    @Modifying
    @Transactional
    @Query("delete from TransferRunEntity r where r.state = :state and r.expiresAt < :now")
    int deleteExpired(@Param("state") TransferState state, @Param("now") Instant now);
}
