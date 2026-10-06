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
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Read side of the audit trail — filtered, paged, newest first (non-negotiable #3). */
@Service
@RequiredArgsConstructor
public class AuditQueryService {

    private final AuditEventRepository events;
    private final ClusterAccessGuard clusterAccess;
    private final PermissionResolver permissions;
    private final ObjectMapper mapper;

    /** The parameters that name a queue or address other than the one the event is about. */
    private static final Set<String> RESOURCE_PARAMS =
            Set.of("source", "target", "targetQueue", "stagingQueue", "queue", "address", "forwardingAddress");

    private static final String HIDDEN = "(a queue or address you may not read)";

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
        Redaction redaction = everything ? null : redaction(clusterId);
        return PagedView.of(result, e -> toView(e, redaction));
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
                .map(e -> toView(e, everything ? null : redaction(clusterId)))
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
                e -> toView(e, null));
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

    /** What a reader who does not read the whole trail may see of the resources an event's parameters name. */
    private record Redaction(ResourceFilter queues, ResourceFilter addresses) {

        boolean sees(String value) {
            // A transfer names its ends as "node/queue".
            String name = value.substring(value.lastIndexOf('/') + 1);
            return queues.readable(name) || addresses.readable(name);
        }
    }

    private Redaction redaction(UUID clusterId) {
        return new Redaction(
                permissions.filter(clusterId, ResourceKind.QUEUE), permissions.filter(clusterId, ResourceKind.ADDRESS));
    }

    /**
     * An event about a queue the caller may read can still name another in its parameters, such as where a
     * message was moved or a transfer sent them. A parameter that names a queue or address the caller may not read
     * is replaced, and so is the cluster a transfer went to when one was.
     */
    private AuditEventView toView(AuditEventEntity e, Redaction redaction) {
        String params = redaction == null || e.getParams() == null ? e.getParams() : redacted(e.getParams(), redaction);
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
                params,
                e.getError(),
                e.getClusterName(),
                e.getNodeId());
    }

    private String redacted(String paramsJson, Redaction redaction) {
        JsonNode params = mapper.readTree(paramsJson);
        if (!(params instanceof ObjectNode object)) {
            return paramsJson;
        }
        boolean hidden = false;
        for (String key : RESOURCE_PARAMS) {
            JsonNode value = object.get(key);
            if (value != null && value.isString() && !redaction.sees(value.asString())) {
                object.put(key, HIDDEN);
                hidden = true;
            }
        }
        if (hidden) {
            object.remove("targetClusterId");
        }
        return mapper.writeValueAsString(object);
    }
}
