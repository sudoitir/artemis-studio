package io.github.sudoitir.artemisstudio.kernel.audit.internal.persistence;

import jakarta.persistence.QueryHint;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.hibernate.jpa.HibernateHints;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

/** {@code audit_event} access (ADR-0011). */
public interface AuditEventRepository extends JpaRepository<AuditEventEntity, Long> {

    List<AuditEventEntity> findByClusterIdOrderByTsDesc(UUID clusterId);

    Optional<AuditEventEntity> findByIdAndClusterId(Long id, UUID clusterId);

    Page<AuditEventEntity> findByTargetTypeAndTargetNameOrderByTsDesc(
            String targetType, String targetName, org.springframework.data.domain.Pageable page);

    /**
     * The target names whose latest non-preview event is a creation, counting a deletion only
     * when it succeeded: "Studio created this and has no record of removing it". One row per
     * name, so the routing view never loads the target type's whole history. The broker keeps
     * no origin for a divert (ADR-0065 D2), so this audit trail is the only honest source.
     */
    @Query(nativeQuery = true, value = """
            SELECT target_name FROM (
                SELECT DISTINCT ON (target_name) target_name, action
                FROM audit_event
                WHERE cluster_id = :clusterId
                  AND target_type = :targetType
                  AND dry_run = false
                  AND target_name IS NOT NULL
                  AND (action = :created OR (action = :deleted AND outcome = 'SUCCESS'))
                ORDER BY target_name, ts DESC, id DESC
            ) latest
            WHERE action = :created
            """)
    List<String> findOwnedTargetNames(
            @Param("clusterId") UUID clusterId,
            @Param("targetType") String targetType,
            @Param("created") String created,
            @Param("deleted") String deleted);

    /** What an audit read keeps, in the form both reads share; {@code :#{#f.x}} reads field {@code x} of the {@link AuditFilter}. */
    String FILTER = """
            e.clusterId = :#{#f.clusterId}
              and (:#{#f.everything} = true
                   or (e.targetType = 'QUEUE' and e.targetName in :#{#f.queues})
                   or (e.targetType = 'ADDRESS' and e.targetName in :#{#f.addresses}))
              and (:#{#f.username} is null or e.username = :#{#f.username})
              and (:#{#f.action} is null or e.action = :#{#f.action})
              and (:#{#f.outcome} is null or e.outcome = :#{#f.outcome})
              and (:#{#f.parentId} is null or e.parentId = :#{#f.parentId})
              and e.ts >= :#{#f.from}
              and e.ts <= :#{#f.to}
            """;

    /** Filtered, newest-first page for the audit-log screen. */
    @Query("select e from AuditEventEntity e where " + FILTER + " order by e.ts desc")
    Page<AuditEventEntity> findPage(@Param("f") AuditFilter f, Pageable pageable);

    /**
     * The same rows as {@link #findPage}, oldest first, as a cursor for an export: the caller closes the stream,
     * inside a transaction, and detaches each entity once written so memory stays bounded.
     */
    @QueryHints({
        @QueryHint(name = HibernateHints.HINT_FETCH_SIZE, value = "500"),
        @QueryHint(name = HibernateHints.HINT_READ_ONLY, value = "true")
    })
    @Query("select e from AuditEventEntity e where " + FILTER + " order by e.ts asc, e.id asc")
    Stream<AuditEventEntity> streamFiltered(@Param("f") AuditFilter f);

    /** The names of one kind of target the cluster's events are about, for deciding which a reader may see. */
    @Query("select distinct e.targetName from AuditEventEntity e"
            + " where e.clusterId = :clusterId and e.targetType = :targetType and e.targetName is not null")
    List<String> findDistinctTargetNames(@Param("clusterId") UUID clusterId, @Param("targetType") String targetType);
}
