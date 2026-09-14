package io.github.sudoitir.artemisstudio.kernel.audit;

import io.github.sudoitir.artemisstudio.kernel.audit.internal.persistence.AuditEventEntity;
import io.github.sudoitir.artemisstudio.kernel.audit.internal.persistence.AuditEventRepository;
import io.github.sudoitir.artemisstudio.kernel.security.Actor;
import io.github.sudoitir.artemisstudio.kernel.security.ScopeHierarchy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes the audit trail for mutating actions (ADR-0002 non-negotiable #3).
 *
 * <p>The caller runs inside a transaction, calls {@link #begin} <em>before</em>
 * the broker call, then {@link #succeed} or {@link #fail} — all in that one
 * transaction, so an action and its audit row commit or roll back together.
 * {@code begin} returns the event; pass it back to record the outcome.
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditEventRepository events;
    private final ObjectMapper mapper;
    private final ScopeHierarchy clusters;
    /** Resolved lazily: the content policy that implements it itself writes audit rows. */
    private final ObjectProvider<AuditParamsFilter> paramsFilter;

    public AuditEvent begin(
            Actor actor,
            String action,
            String targetType,
            String targetName,
            UUID clusterId,
            UUID nodeId,
            Map<String, ?> params,
            boolean dryRun) {
        AuditParamsFilter filter = paramsFilter.getIfAvailable();
        Map<String, ?> written =
                (params == null || params.isEmpty() || filter == null) ? params : filter.filter(params);
        String paramsJson = (written == null || written.isEmpty()) ? null : mapper.writeValueAsString(written);
        Actor a = actor == null ? Actor.system() : actor;
        return events.save(new AuditEventEntity(
                action,
                targetType,
                targetName,
                a.displayName(),
                a.requestId(),
                a.sourceIp(),
                a.userId(),
                clusterId,
                clusterId == null ? null : clusters.clusterName(clusterId),
                nodeId,
                paramsJson,
                dryRun));
    }

    /**
     * Every non-preview row for one target type on a cluster, oldest first, so a module
     * can fold them into "what does Studio still own" — the routing view's only record
     * of the diverts Studio created, because the broker keeps none (ADR-0065 D2).
     */
    public List<AuditEvent> history(UUID clusterId, String targetType) {
        return List.copyOf(events.findByClusterIdAndTargetTypeAndDryRunFalseOrderByTsAsc(clusterId, targetType));
    }

    public void succeed(AuditEvent event, long affectedCount) {
        AuditEventEntity entity = entity(event);
        entity.markSuccess(affectedCount);
        events.save(entity);
    }

    public void fail(AuditEvent event, String error) {
        AuditEventEntity entity = entity(event);
        entity.markFailure(error);
        events.save(entity);
    }

    /**
     * Record the outcome of a cluster-wide fan-out (ADR-0049 D4). One row carries
     * the whole command, with {@code detail} holding the per-node result, so a
     * partially applied command is reconstructable afterwards.
     *
     * <p>A command that failed on any node is recorded as failed even though other
     * nodes applied — {@code detail} says which. Recording it as success because
     * most of it worked is the lie the audit log exists to prevent.
     */
    public void finish(AuditEvent event, boolean anyFailed, long affectedCount, String error, Object detail) {
        AuditEventEntity entity = entity(event);
        entity.attachOutcomeDetail(detail == null ? null : mapper.writeValueAsString(detail));
        if (anyFailed) {
            entity.markFailure(error);
        } else {
            entity.markSuccess(affectedCount);
        }
        events.save(entity);
    }

    /** Every {@link AuditEvent} is one this service began, so it is always the entity. */
    private static AuditEventEntity entity(AuditEvent event) {
        return (AuditEventEntity) event;
    }
}
