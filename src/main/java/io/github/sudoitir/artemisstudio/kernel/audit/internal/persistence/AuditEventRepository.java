package io.github.sudoitir.artemisstudio.kernel.audit.internal.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
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

    /** The names of one kind of target the cluster's events are about, for deciding which a reader may see. */
    @Query("select distinct e.targetName from AuditEventEntity e"
            + " where e.clusterId = :clusterId and e.targetType = :targetType and e.targetName is not null")
    List<String> findDistinctTargetNames(@Param("clusterId") UUID clusterId, @Param("targetType") String targetType);
}
