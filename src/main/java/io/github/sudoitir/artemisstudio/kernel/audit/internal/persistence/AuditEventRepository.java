package io.github.sudoitir.artemisstudio.kernel.audit.internal.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** {@code audit_event} access (ADR-0011). */
public interface AuditEventRepository extends JpaRepository<AuditEventEntity, Long> {

    List<AuditEventEntity> findByClusterIdOrderByTsDesc(UUID clusterId);

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

    /**
     * Filtered, newest-first page for the audit-log screen. String filters are
     * optional ({@code null} drops the predicate); the caller always passes a
     * {@code from}/{@code to} window (widened to sentinels when unset) — Postgres
     * cannot infer the type of a timestamp parameter used only in an {@code is null}
     * test.
     */
    @Query("""
            select e from AuditEventEntity e
            where e.clusterId = :clusterId
              and (:username is null or e.username = :username)
              and (:action is null or e.action = :action)
              and (:outcome is null or e.outcome = :outcome)
              and e.ts >= :from
              and e.ts <= :to
            order by e.ts desc
            """)
    Page<AuditEventEntity> findPage(
            @Param("clusterId") UUID clusterId,
            @Param("username") String username,
            @Param("action") String action,
            @Param("outcome") String outcome,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable);
}
