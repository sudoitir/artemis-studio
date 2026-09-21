package io.github.sudoitir.artemisstudio.kernel.audit;

import io.github.sudoitir.artemisstudio.kernel.audit.internal.AuditRowWriter;
import io.github.sudoitir.artemisstudio.kernel.audit.internal.persistence.AuditEventEntity;
import io.github.sudoitir.artemisstudio.kernel.audit.internal.persistence.AuditEventRepository;
import io.github.sudoitir.artemisstudio.kernel.security.Actor;
import io.github.sudoitir.artemisstudio.kernel.security.ScopeHierarchy;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes the audit trail for mutating actions (non-negotiable #3, ADR-0078).
 *
 * <p>{@link #begin} commits a {@code PENDING} row on its own, so it is durable before the broker
 * call it describes; pass the returned event to {@link #succeed}, {@link #fail} or {@link #finish},
 * which commit the outcome on their own too. A broker is not a participant in the caller's
 * transaction, so neither half may depend on that transaction committing.
 *
 * <p>An event begun while {@link AuditScope#PARENT} is bound names that event as its parent.
 *
 * <p>When the caller's transaction rolls back before an outcome was recorded, the row is failed
 * with that reason, so an action that did not happen never leaves a row that looks in flight.
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    static final String ROLLED_BACK = "The action was rolled back before its outcome was recorded.";

    private final AuditRowWriter writer;
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
        AuditEventEntity entity = new AuditEventEntity(
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
                dryRun);
        entity.attachParent(AuditScope.PARENT.isBound() ? AuditScope.PARENT.get() : null);
        AuditEventEntity row = writer.insert(entity);
        failIfCallerRollsBack(row.getId());
        return row;
    }

    /**
     * The target names Studio created and has no record of removing, for one target type on a
     * cluster: the latest non-preview event per name is a creation, where only a successful
     * deletion counts. The routing view's ownership (ADR-0065 D2).
     */
    public java.util.Set<String> ownedTargetNames(
            UUID clusterId, String targetType, String createdAction, String deletedAction) {
        return java.util.Set.copyOf(events.findOwnedTargetNames(clusterId, targetType, createdAction, deletedAction));
    }

    /** An event by id, to record the outcome of work that outlived the thread which began it (ADR-0093). */
    public java.util.Optional<AuditEvent> byId(Long id) {
        return events.findById(id).map(AuditEvent.class::cast);
    }

    public void succeed(AuditEvent event, long affectedCount) {
        writer.succeed(event.getId(), affectedCount);
    }

    public void fail(AuditEvent event, String error) {
        writer.fail(event.getId(), error);
    }

    /** An action that failed after affecting some of its targets; the count is recorded with the error. */
    public void failPartial(AuditEvent event, long affectedCount, String error) {
        writer.failPartial(event.getId(), affectedCount, error);
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
        writer.finish(
                event.getId(),
                anyFailed,
                affectedCount,
                error,
                detail == null ? null : mapper.writeValueAsString(detail));
    }

    private void failIfCallerRollsBack(Long id) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    writer.failIfPending(id, ROLLED_BACK);
                }
            }
        });
    }
}
