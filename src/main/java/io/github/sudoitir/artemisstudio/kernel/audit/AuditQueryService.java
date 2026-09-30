package io.github.sudoitir.artemisstudio.kernel.audit;

import io.github.sudoitir.artemisstudio.kernel.audit.internal.persistence.AuditEventEntity;
import io.github.sudoitir.artemisstudio.kernel.audit.internal.persistence.AuditEventRepository;
import io.github.sudoitir.artemisstudio.kernel.audit.web.AuditViews.AuditEventView;
import io.github.sudoitir.artemisstudio.kernel.core.NotFoundException;
import io.github.sudoitir.artemisstudio.kernel.core.PagedView;
import io.github.sudoitir.artemisstudio.kernel.core.ResourceQuery;
import io.github.sudoitir.artemisstudio.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.artemisstudio.kernel.security.Permissions;
import java.time.Instant;
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
    public PagedView<AuditEventView> page(UUID clusterId, AuditQuery query) {
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_READ);
        Page<AuditEventEntity> result = events.findPage(
                clusterId,
                blankToNull(query.username()),
                blankToNull(query.action()),
                blankToNull(query.outcome()),
                query.parentId(),
                query.from() != null ? query.from() : Instant.EPOCH,
                query.to() != null ? query.to() : Instant.parse("9999-12-31T23:59:59Z"),
                PageRequest.of(query.page() - 1, query.size()));
        return PagedView.of(result, AuditQueryService::toView);
    }

    @Transactional(readOnly = true)
    public AuditEventView get(UUID clusterId, long id) {
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_READ);
        return events.findByIdAndClusterId(id, clusterId)
                .map(AuditQueryService::toView)
                .orElseThrow(() -> new NotFoundException("audit event", id));
    }

    /**
     * The latest events about one Studio-wide target, newest first — a plugin's history. Not
     * cluster-scoped, so the caller enforces who may read it.
     */
    @Transactional(readOnly = true)
    public PagedView<AuditEventView> forTarget(String targetType, String targetName, ResourceQuery query) {
        return PagedView.of(
                events.findByTargetTypeAndTargetNameOrderByTsDesc(
                        targetType, targetName, PageRequest.of(query.page() - 1, query.size())),
                AuditQueryService::toView);
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
