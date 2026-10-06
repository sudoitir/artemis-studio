package io.github.sudoitir.artemisstudio.kernel.audit;

import io.github.sudoitir.artemisstudio.kernel.audit.internal.persistence.AuditEventEntity;
import io.github.sudoitir.artemisstudio.kernel.audit.internal.persistence.AuditEventRepository;
import io.github.sudoitir.artemisstudio.kernel.audit.web.AuditViews.AuditEventView;
import io.github.sudoitir.artemisstudio.kernel.core.NotFoundException;
import io.github.sudoitir.artemisstudio.kernel.core.PagedView;
import io.github.sudoitir.artemisstudio.kernel.core.ResourceQuery;
import io.github.sudoitir.artemisstudio.kernel.plugin.ResourceKind;
import io.github.sudoitir.artemisstudio.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.artemisstudio.kernel.security.PermissionResolver;
import io.github.sudoitir.artemisstudio.kernel.security.Permissions;
import io.github.sudoitir.artemisstudio.kernel.security.ResourceFilter;
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
    private final PermissionResolver permissions;

    @Transactional(readOnly = true)
    public PagedView<AuditEventView> page(UUID clusterId, AuditQuery query) {
        clusterAccess.requireVisible(clusterId);
        // A caller who reads the cluster reads its whole trail. Any other reads the events about the queues and
        // addresses they may read, chosen in the query so a total never counts the rest.
        boolean everything = clusterAccess.holds(clusterId, Permissions.CLUSTER_READ);
        Page<AuditEventEntity> result = events.findPage(
                clusterId,
                everything,
                everything ? List.of("") : readable(clusterId, "QUEUE", ResourceKind.QUEUE),
                everything ? List.of("") : readable(clusterId, "ADDRESS", ResourceKind.ADDRESS),
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
        clusterAccess.requireVisible(clusterId);
        boolean everything = clusterAccess.holds(clusterId, Permissions.CLUSTER_READ);
        ResourceFilter queues = permissions.filter(clusterId, ResourceKind.QUEUE);
        ResourceFilter addresses = permissions.filter(clusterId, ResourceKind.ADDRESS);
        return events.findByIdAndClusterId(id, clusterId)
                .filter(e -> everything
                        || ("QUEUE".equals(e.getTargetType()) && queues.readable(e.getTargetName()))
                        || ("ADDRESS".equals(e.getTargetType()) && addresses.readable(e.getTargetName())))
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

    /** The names of one target type that the caller may read; never empty, as an empty collection cannot be bound. */
    private List<String> readable(UUID clusterId, String targetType, ResourceKind kind) {
        ResourceFilter filter = permissions.filter(clusterId, kind);
        List<String> names = events.findDistinctTargetNames(clusterId, targetType).stream()
                .filter(filter::readable)
                .toList();
        return names.isEmpty() ? List.of("") : names;
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
