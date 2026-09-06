package io.github.sudoitir.artemisstudio.persist;

import io.github.sudoitir.artemisstudio.security.Actor;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes the audit trail for mutating actions (ADR-0002 non-negotiable #3).
 *
 * <p>The caller runs inside a transaction, calls {@link #begin} <em>before</em>
 * the broker call, then {@link #succeed} or {@link #fail} — all in that one
 * transaction, so an action and its audit row commit or roll back together.
 * {@code begin} returns the managed entity; pass it back to record the outcome.
 * Until authentication lands (Phase 8) every actor is {@code 'system'}.
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditEventRepository events;
    private final ObjectMapper mapper;

    public AuditEventEntity begin(
            Actor actor,
            String action,
            String targetType,
            String targetName,
            UUID clusterId,
            UUID nodeId,
            Map<String, ?> params,
            boolean dryRun) {
        String paramsJson = (params == null || params.isEmpty()) ? null : mapper.writeValueAsString(params);
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
                nodeId,
                paramsJson,
                dryRun));
    }

    public void succeed(AuditEventEntity event, long affectedCount) {
        event.markSuccess(affectedCount);
        events.save(event);
    }

    public void fail(AuditEventEntity event, String error) {
        event.markFailure(error);
        events.save(event);
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
    public void finish(AuditEventEntity event, boolean anyFailed, long affectedCount, String error, Object detail) {
        event.attachOutcomeDetail(detail == null ? null : mapper.writeValueAsString(detail));
        if (anyFailed) {
            event.markFailure(error);
        } else {
            event.markSuccess(affectedCount);
        }
        events.save(event);
    }
}
