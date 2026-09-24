package io.github.sudoitir.artemisstudio.kernel.audit;

import io.github.sudoitir.artemisstudio.kernel.audit.internal.persistence.AuditEventEntity;
import io.github.sudoitir.artemisstudio.kernel.audit.internal.persistence.AuditEventRepository;
import io.github.sudoitir.artemisstudio.kernel.audit.web.AuditViews.AuditEventView;
import io.github.sudoitir.artemisstudio.kernel.audit.web.AuditViews.AuditPageView;
import io.github.sudoitir.artemisstudio.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.artemisstudio.kernel.security.Permissions;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side of the audit trail — filtered, paged, newest first (non-negotiable #3). */
@Service
@RequiredArgsConstructor
public class AuditQueryService {

    private final AuditEventRepository events;
    private final ClusterAccessGuard clusterAccess;

    @Transactional(readOnly = true)
    public AuditPageView page(
            UUID clusterId,
            String username,
            String action,
            String outcome,
            Long parentId,
            Instant from,
            Instant to,
            int page,
            int size) {
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_READ);
        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), 500);
        Page<AuditEventEntity> result = events.findPage(
                clusterId,
                blankToNull(username),
                blankToNull(action),
                blankToNull(outcome),
                parentId,
                from != null ? from : Instant.EPOCH,
                to != null ? to : Instant.parse("9999-12-31T23:59:59Z"),
                PageRequest.of(p - 1, s));
        return new AuditPageView(
                result.getContent().stream().map(AuditQueryService::toView).toList(), result.getTotalElements(), p, s);
    }

    /**
     * The latest events about one Studio-wide target, newest first — a plugin's history. Not
     * cluster-scoped, so the caller enforces who may read it.
     */
    @Transactional(readOnly = true)
    public List<AuditEventView> forTarget(String targetType, String targetName, int limit) {
        return events
                .findByTargetTypeAndTargetNameOrderByTsDesc(
                        targetType, targetName, PageRequest.of(0, Math.min(Math.max(limit, 1), 500)))
                .stream()
                .map(AuditQueryService::toView)
                .toList();
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    private static AuditEventView toView(AuditEventEntity e) {
        return new AuditEventView(
                e.getId(),
                e.getParentId(),
                e.getTs(),
                e.getUsername(),
                e.getSourceIp(),
                e.getRequestId(),
                e.getAction(),
                e.getTargetType(),
                e.getTargetName(),
                e.getAffectedCount(),
                e.getOutcome(),
                e.isDryRun(),
                e.getParams(),
                e.getError(),
                e.getClusterName(),
                e.getNodeId());
    }
}
