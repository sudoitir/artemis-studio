package io.github.sudoitir.artemisstudio.kernel.audit;

import io.github.sudoitir.artemisstudio.kernel.audit.internal.persistence.AuditEventEntity;
import io.github.sudoitir.artemisstudio.kernel.audit.internal.persistence.AuditEventRepository;
import io.github.sudoitir.artemisstudio.kernel.audit.web.AuditViews.AuditEventView;
import io.github.sudoitir.artemisstudio.kernel.audit.web.AuditViews.AuditExportRow;
import io.github.sudoitir.artemisstudio.kernel.core.NotFoundException;
import io.github.sudoitir.artemisstudio.kernel.core.PagedView;
import io.github.sudoitir.artemisstudio.kernel.core.ResourceQuery;
import io.github.sudoitir.artemisstudio.kernel.plugin.ResourceKind;
import io.github.sudoitir.artemisstudio.kernel.security.ActorResolver;
import io.github.sudoitir.artemisstudio.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.artemisstudio.kernel.security.PermissionResolver;
import io.github.sudoitir.artemisstudio.kernel.security.Permissions;
import io.github.sudoitir.artemisstudio.kernel.security.ResourceFilter;
import io.github.sudoitir.artemisstudio.kernel.security.ScopeHierarchy;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Read side of the audit trail — filtered, paged, newest first, or exported oldest first (non-negotiable #3). */
@Service
@RequiredArgsConstructor
public class AuditQueryService {

    private final AuditEventRepository events;
    private final ClusterAccessGuard clusterAccess;
    private final PermissionResolver permissions;
    private final ObjectMapper mapper;
    private final AuditService audit;
    private final ActorResolver actors;
    private final ScopeHierarchy clusters;
    private final EntityManager entityManager;
    private final TransactionTemplate transactions;

    /** The parameters that name a queue or address other than the one the event is about. */
    private static final Set<String> RESOURCE_PARAMS =
            Set.of("source", "target", "targetQueue", "stagingQueue", "queue", "address", "forwardingAddress");

    private static final String HIDDEN = "(a queue or address you may not read)";

    @Transactional(readOnly = true)
    public PagedView<AuditEventView> page(UUID clusterId, AuditQuery query) {
        clusterAccess.requireVisible(clusterId);
        Scope scope = scope(clusterId);
        Page<AuditEventEntity> result = events.findPage(
                clusterId,
                scope.everything(),
                scope.queues(),
                scope.addresses(),
                blankToNull(query.username()),
                blankToNull(query.action()),
                blankToNull(query.outcome()),
                query.parentId(),
                from(query),
                to(query),
                PageRequest.of(query.page() - 1, query.size()));
        return PagedView.of(result, e -> toView(e, scope.redaction()));
    }

    /**
     * An export that has been authorised and recorded, to run on any thread: everything the caller's rights decide
     * is settled here, so the run needs no request or security context.
     */
    public static final class Export {

        private final UUID clusterId;
        private final String clusterName;
        private final AuditEvent event;
        private final Scope scope;
        private final AuditQuery query;

        private Export(UUID clusterId, String clusterName, AuditEvent event, Scope scope, AuditQuery query) {
            this.clusterId = clusterId;
            this.clusterName = clusterName;
            this.event = event;
            this.scope = scope;
            this.query = query;
        }

        /** The cluster's name, for the file's name. */
        public String clusterName() {
            return clusterName;
        }
    }

    /**
     * Authorise an export of the cluster's trail under the filters of {@code query} (its page and size are not used)
     * and record it as started. The caller needs {@link AuditPermissions#EXPORT} and sees what {@link #page} shows:
     * the whole trail with {@code cluster:read}, otherwise the events about the queues and addresses they may read.
     */
    public Export prepareExport(UUID clusterId, AuditQuery query, String format) {
        clusterAccess.requireVisible(clusterId);
        clusterAccess.requireCluster(clusterId, AuditPermissions.EXPORT);
        Scope scope = scope(clusterId);
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("user", blankToNull(query.username()));
        filters.put("action", blankToNull(query.action()));
        filters.put("outcome", blankToNull(query.outcome()));
        filters.put("parentId", query.parentId());
        filters.put("from", query.from());
        filters.put("to", query.to());
        filters.values().removeIf(java.util.Objects::isNull);
        String clusterName = clusters.clusterName(clusterId);
        AuditEvent event = audit.begin(
                actors.resolve(),
                "EXPORT_AUDIT",
                "AUDIT",
                clusterName,
                clusterId,
                null,
                Map.of("format", format, "filters", filters),
                false);
        return new Export(clusterId, clusterName, event, scope, query);
    }

    /**
     * Write the export's rows, oldest first, to {@code sink} and record the outcome: the count on success, how far it
     * got when the sink fails (the client went away) or the read does. The export's own row is left out.
     */
    public void run(Export export, Consumer<AuditExportRow> sink) {
        long[] written = {0};
        try {
            transactions.executeWithoutResult(tx -> {
                try (Stream<AuditEventEntity> rows = events.streamFiltered(
                        export.clusterId,
                        export.scope.everything(),
                        export.scope.queues(),
                        export.scope.addresses(),
                        blankToNull(export.query.username()),
                        blankToNull(export.query.action()),
                        blankToNull(export.query.outcome()),
                        export.query.parentId(),
                        from(export.query),
                        to(export.query))) {
                    rows.forEach(e -> {
                        if (!e.getId().equals(export.event.getId())) {
                            sink.accept(new AuditExportRow(
                                    toView(e, export.scope.redaction()),
                                    export.scope.everything() ? e.getOutcomeDetail() : null));
                            written[0]++;
                        }
                        entityManager.detach(e);
                    });
                }
            });
        } catch (RuntimeException ex) {
            // A write that fails is the client going away; anything else is the read.
            String how = ex instanceof JacksonIOException ? "cancelled" : "failed";
            audit.failPartial(export.event, written[0], how + " after " + written[0] + " rows");
            throw ex;
        }
        audit.succeed(export.event, written[0]);
    }

    /** What the caller reads of a cluster's trail, resolved once. */
    private record Scope(boolean everything, List<String> queues, List<String> addresses, Redaction redaction) {}

    /**
     * A caller who reads the cluster reads its whole trail. Any other reads the events about the queues and
     * addresses they may read, chosen in the query so a total never counts the rest.
     */
    private Scope scope(UUID clusterId) {
        boolean everything = clusterAccess.holds(clusterId, Permissions.CLUSTER_READ);
        return new Scope(
                everything,
                everything ? List.of("") : readable(clusterId, "QUEUE", ResourceKind.QUEUE),
                everything ? List.of("") : readable(clusterId, "ADDRESS", ResourceKind.ADDRESS),
                everything ? null : redaction(clusterId));
    }

    private static Instant from(AuditQuery query) {
        return query.from() != null ? query.from() : Instant.EPOCH;
    }

    private static Instant to(AuditQuery query) {
        return query.to() != null ? query.to() : Instant.parse("9999-12-31T23:59:59Z");
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
