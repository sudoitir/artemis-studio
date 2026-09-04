package io.github.sudoitir.artemisstudio.persist;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** {@code broker_event} read access (ADR-0028). Rows are written by {@link BrokerEventWriter}. */
public interface BrokerEventRepository extends JpaRepository<BrokerEventEntity, Long> {

    /** The just-inserted rows a flush produced, in seq order, for the SSE fan-out. */
    List<BrokerEventEntity> findBySeqGreaterThanOrderBySeqAsc(long seq);

    /** Highest seq currently persisted, or empty when the table is empty. */
    Optional<BrokerEventEntity> findFirstByOrderBySeqDesc();

    /** Bounded replay for a reconnecting SSE client (slice 3). */
    List<BrokerEventEntity> findByClusterIdAndSeqGreaterThanOrderBySeqAsc(UUID clusterId, long seq, Pageable pageable);

    /** The oldest still-retained event's time, for the history API envelope. */
    @Query("select min(e.occurredAt) from BrokerEventEntity e where e.clusterId = :clusterId")
    Instant oldestRetained(@Param("clusterId") UUID clusterId);

    /**
     * Filtered, newest-first page for the events screen. Optional string filters
     * ({@code null} drops the predicate); the caller always passes a
     * {@code from}/{@code to} window widened to sentinels when unset.
     */
    @Query("""
            select e from BrokerEventEntity e
            where e.clusterId = :clusterId
              and (:type is null or e.type = :type)
              and (:nodeId is null or e.nodeId = :nodeId)
              and (:address is null or e.address = :address)
              and e.occurredAt >= :from
              and e.occurredAt <= :to
            order by e.seq desc
            """)
    Page<BrokerEventEntity> findPage(
            @Param("clusterId") UUID clusterId,
            @Param("type") String type,
            @Param("nodeId") UUID nodeId,
            @Param("address") String address,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable);
}
