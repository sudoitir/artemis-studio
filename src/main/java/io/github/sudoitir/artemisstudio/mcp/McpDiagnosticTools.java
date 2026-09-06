package io.github.sudoitir.artemisstudio.mcp;

import io.github.sudoitir.artemisstudio.config.ArtemisStudioProperties;
import io.github.sudoitir.artemisstudio.persist.ClusterRepository;
import io.github.sudoitir.artemisstudio.security.PermissionResolver;
import io.github.sudoitir.artemisstudio.security.Permissions;
import io.github.sudoitir.artemisstudio.service.AlertService;
import io.github.sudoitir.artemisstudio.service.AuditQueryService;
import io.github.sudoitir.artemisstudio.service.BrokerEventService;
import io.github.sudoitir.artemisstudio.service.ClusterService;
import io.github.sudoitir.artemisstudio.service.ConfigDiffService;
import io.github.sudoitir.artemisstudio.service.CrossNodeAggregator;
import io.github.sudoitir.artemisstudio.service.MessageService;
import io.github.sudoitir.artemisstudio.service.MetricQueryService;
import io.github.sudoitir.artemisstudio.service.PagedListService;
import io.github.sudoitir.artemisstudio.service.RequestReplyService;
import io.github.sudoitir.artemisstudio.service.ResourceQuery;
import io.github.sudoitir.artemisstudio.service.RrMetrics;
import io.github.sudoitir.artemisstudio.web.dto.AuditViews;
import io.github.sudoitir.artemisstudio.web.dto.ClusterViews;
import io.github.sudoitir.artemisstudio.web.dto.ConfigViews;
import io.github.sudoitir.artemisstudio.web.dto.EventViews;
import io.github.sudoitir.artemisstudio.web.dto.MessageViews;
import io.github.sudoitir.artemisstudio.web.dto.MetricViews;
import io.github.sudoitir.artemisstudio.web.dto.ResourceViews;
import io.github.sudoitir.artemisstudio.web.dto.RrViews;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * The read half of the MCP surface (ADR-0045). Nothing here mutates, so nothing
 * here needs the dry-run/confirm contract — that is the point of the split from
 * {@link McpTuningTools}, and it is the review boundary: a tool that belongs in
 * the other file is visible as such from its signature alone.
 *
 * <p>Every method is a thin adapter. Authorization, auditing, rate limiting and
 * caching all stay in {@code service/**} where the REST layer already exercises
 * them; adding a check here would put policy in an adapter and let the two drift.
 */
@Component
@RequiredArgsConstructor
public class McpDiagnosticTools {

    private final ArtemisStudioProperties props;
    private final ClusterService clusters;
    private final CrossNodeAggregator queues;
    private final PagedListService lists;
    private final MetricQueryService metrics;
    private final ConfigDiffService configDiff;
    private final MessageService messages;
    private final RequestReplyService requestReply;
    private final RrMetrics rrMetrics;
    private final BrokerEventService brokerEvents;
    private final AuditQueryService auditLog;
    private final io.github.sudoitir.artemisstudio.persist.BrokerNodeRepository nodeRepo;
    private final ClusterRepository clusterRepo;
    private final AlertService alerts;
    private final PermissionResolver perm;
    private final io.github.sudoitir.artemisstudio.service.ClockOffsetService clocks;

    @McpTool(
            name = "cluster_health",
            description =
                    "HA role per node, who is live, split-brain, replication lag, " + "firing alerts. Start here.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult clusterHealth(@McpToolParam(required = true) String clusterId) {
        UUID id = McpArgs.uuid("clusterId", clusterId);
        return McpErrors.guard(() -> health(id));
    }

    private McpViews.ClusterHealth health(UUID clusterId) {
        // Deliberately not ClusterService.get(): that assembles the full cluster screen
        // and runs a live capability probe against a broker node, which fails the whole
        // call when the cluster is exactly what a triage step is asking about — down.
        // topology() and health() read the scrape cache and answer from what is known.
        ClusterViews.TopologyView topology = clusters.topology(clusterId);
        ClusterViews.HealthView h = clusters.health(clusterId);
        // Both calls above went through ClusterAccessGuard, so by here the caller is
        // known to hold cluster:read on this id and the name is not a disclosure.
        String name = clusterRepo
                .findById(clusterId)
                .map(io.github.sudoitir.artemisstudio.persist.ClusterEntity::getName)
                .orElseThrow(
                        () -> new io.github.sudoitir.artemisstudio.service.NotFoundException("cluster", clusterId));

        List<McpViews.NodeHealth> nodes = new ArrayList<>();
        for (ClusterViews.LogicalNodeView logical : topology.nodes()) {
            for (ClusterViews.NodeEndpointView e : logical.endpoints()) {
                nodes.add(new McpViews.NodeHealth(
                        e.name(), e.haRole(), e.active(), e.manageable(), e.artemisNodeId(), e.lastSeenAt()));
            }
        }

        // alert:read is a separate permission from cluster:read, so a key can legally
        // see health and not alerts. Reporting that explicitly beats an empty list a
        // model would read as "nothing is firing" (non-negotiable #5).
        boolean alertsVisible = perm.can(clusterId, Permissions.ALERT_READ);
        List<McpViews.FiringAlert> firing = alertsVisible
                ? alerts.firingNow(clusterId).stream()
                        .map(f -> new McpViews.FiringAlert(
                                f.ruleName(), f.subjectKey(), f.severity(), f.value(), f.startedAt()))
                        .toList()
                : List.of();

        return new McpViews.ClusterHealth(
                clusterId,
                name,
                h.level(),
                h.splitBrain(),
                h.replicationBehind(),
                h.liveEndpointNames(),
                nodes,
                h.notes(),
                alertsVisible,
                firing,
                Instant.now(),
                clockVerdict(clusterId));
    }

    private McpViews.ClockVerdict clockVerdict(UUID clusterId) {
        var assessment = clocks.assessmentFor(clusterId);
        return new McpViews.ClockVerdict(
                assessment.verdict().name(),
                assessment.worst().map(w -> w.offset().offsetMs()).orElse(null),
                assessment.worst().map(w -> w.offset().uncertaintyMs()).orElse(null),
                assessment.skewed().stream()
                        .map(io.github.sudoitir.artemisstudio.service.ClockOffsetService.NodeSkew::nodeName)
                        .toList(),
                assessment.at());
    }

    // ---- list_resources ---------------------------------------------------

    /**
     * The six resource lists behind one discriminator (design D5). Six tools would
     * cost six schemas on every {@code tools/list} to describe what is, to a model,
     * one question with a parameter.
     */
    private enum ListKind {
        QUEUES,
        ADDRESSES,
        CONSUMERS,
        SESSIONS,
        CONNECTIONS,
        PRODUCERS
    }

    @McpTool(
            name = "list_resources",
            description = "Merged cross-node listing of one kind of cluster resource. Capped and ordered.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult listResources(
            @McpToolParam(required = true) String clusterId,
            @McpToolParam(description = "The resource kind", required = true) String kind,
            @McpToolParam(description = "Substring filter", required = false) String filter,
            @McpToolParam(description = "Max rows", required = false) Integer limit) {
        UUID id = McpArgs.uuid("clusterId", clusterId);
        ListKind k = McpArgs.enumOf(ListKind.class, "kind", kind, null);
        int capped = props.mcp().clamp(limit);
        return McpErrors.guard(() -> resources(id, k, filter, capped));
    }

    private McpViews.Page<McpViews.ResourceRow> resources(UUID clusterId, ListKind kind, String filter, int limit) {
        // One row over the cap is fetched so "truncated" is a fact, not a guess: a
        // page of exactly `limit` rows is ambiguous about whether more exist.
        ResourceQuery query = ResourceQuery.of(filter, 1, limit + 1, null);
        List<McpViews.ResourceRow> rows = new ArrayList<>();
        String orderedBy;
        switch (kind) {
            case QUEUES -> {
                orderedBy = "queueName";
                // Queues keep their own snapshot-backed path — they are aggregated from
                // queue_snapshot, not fanned out live, and that is the cheap read.
                for (ResourceViews.QueueView q : queues.queues(clusterId, query).data()) {
                    rows.add(new McpViews.ResourceRow(
                            "queue",
                            q.queueName(),
                            q.nodesPresent() + "/" + q.nodesTotal() + " nodes",
                            "address=" + q.address() + " consumers=" + q.totalConsumerCount(),
                            q.totalMessageCount()));
                }
            }
            case ADDRESSES -> {
                orderedBy = "name";
                for (ResourceViews.AddressView a :
                        lists.addresses(clusterId, query).data()) {
                    rows.add(new McpViews.ResourceRow(
                            "address", a.name(), a.nodeName(), "queues=" + a.queueCount(), a.messageCount()));
                }
            }
            case CONSUMERS -> {
                orderedBy = "queueName";
                for (ResourceViews.ConsumerView c :
                        lists.consumers(clusterId, query).data()) {
                    rows.add(new McpViews.ResourceRow(
                            "consumer",
                            c.queueName(),
                            c.nodeName(),
                            "protocol=" + c.protocol() + " acked=" + c.messagesAcknowledged(),
                            c.messagesDelivered()));
                }
            }
            case SESSIONS -> {
                orderedBy = "sessionId";
                for (ResourceViews.SessionView x :
                        lists.sessions(clusterId, query).data()) {
                    rows.add(new McpViews.ResourceRow(
                            "session",
                            x.sessionId(),
                            x.nodeName(),
                            "user=" + x.user() + " producers=" + x.producerCount(),
                            x.consumerCount()));
                }
            }
            case CONNECTIONS -> {
                orderedBy = "remoteAddress";
                for (ResourceViews.ConnectionView x :
                        lists.connections(clusterId, query).data()) {
                    rows.add(new McpViews.ResourceRow(
                            "connection",
                            x.remoteAddress(),
                            x.nodeName(),
                            "protocol=" + x.protocol() + " client=" + x.clientId(),
                            x.sessionCount()));
                }
            }
            case PRODUCERS -> {
                orderedBy = "address";
                for (ResourceViews.ProducerView x :
                        lists.producers(clusterId, query).data()) {
                    rows.add(new McpViews.ResourceRow(
                            "producer", x.address(), x.nodeName(), "protocol=" + x.protocol(), x.messagesSent()));
                }
            }
            default -> throw McpErrors.invalidParams("unsupported kind");
        }
        return McpViews.Page.of(rows, limit, orderedBy);
    }

    // ---- metric_series ----------------------------------------------------

    @McpTool(
            name = "metric_series",
            description = "A server-bucketed timeseries for one metric, cluster-wide or for one queue.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult metricSeries(
            @McpToolParam(required = true) String clusterId,
            @McpToolParam(description = "The metric", required = true) String metric,
            @McpToolParam(description = "Omit for the whole cluster", required = false) String queue,
            @McpToolParam(description = "e.g. 15m, 6h, 2d. Default 1h", required = false) String window) {
        UUID id = McpArgs.uuid("clusterId", clusterId);
        String m = McpArgs.required("metric", metric);
        Duration lookback = parseWindow(window);
        return McpErrors.guard(() -> series(id, m, queue, lookback, window));
    }

    private McpViews.MetricSeries series(UUID clusterId, String metric, String queue, Duration window, String label) {
        Instant to = Instant.now();
        boolean perQueue = queue != null && !queue.isBlank();
        MetricViews.MetricSeriesResponse response = metrics.query(
                clusterId,
                List.of(metric),
                perQueue ? "QUEUE" : "CLUSTER",
                perQueue ? queue : null,
                to.minus(window),
                to,
                null);
        List<McpViews.MetricPoint> points = response.series().isEmpty()
                ? List.of()
                : response.series().get(0).points().stream()
                        .map(p -> new McpViews.MetricPoint(p.ts(), p.value()))
                        .toList();
        // The step is what the server actually bucketed at, which is not always what
        // the window implies — saying so keeps a model from over-reading resolution.
        return new McpViews.MetricSeries(
                clusterId,
                metric,
                perQueue ? queue : "cluster",
                (label == null ? "1h" : label) + " @ " + response.step(),
                points);
    }

    /**
     * A duration the way an operator says it ({@code 15m}, {@code 6h}, {@code 2d}),
     * not ISO-8601. Models write the former reliably and {@code PT6H} unreliably.
     */
    private static Duration parseWindow(String window) {
        if (window == null || window.isBlank()) {
            return Duration.ofHours(1);
        }
        String w = window.trim().toLowerCase(Locale.ROOT);
        char unit = w.charAt(w.length() - 1);
        long amount;
        try {
            amount = Long.parseLong(w.substring(0, w.length() - 1));
        } catch (NumberFormatException e) {
            throw McpErrors.invalidParams("window must look like 15m, 6h or 2d.");
        }
        if (amount <= 0) {
            throw McpErrors.invalidParams("window must be positive.");
        }
        return switch (unit) {
            case 's' -> Duration.ofSeconds(amount);
            case 'm' -> Duration.ofMinutes(amount);
            case 'h' -> Duration.ofHours(amount);
            case 'd' -> Duration.ofDays(amount);
            default -> throw McpErrors.invalidParams("window unit must be s, m, h or d.");
        };
    }

    // ---- diagnose_queue ---------------------------------------------------

    @McpTool(
            name = "diagnose_queue",
            description = "One queue end to end: depth and trend, consumers, paused, " + "slow consumers, DLQ, events.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult diagnoseQueue(
            @McpToolParam(required = true) String clusterId, @McpToolParam(required = true) String queue) {
        UUID id = McpArgs.uuid("clusterId", clusterId);
        String name = McpArgs.required("queue", queue);
        return McpErrors.guard(() -> diagnose(id, name));
    }

    private McpViews.QueueDiagnosis diagnose(UUID clusterId, String queue) {
        // The snapshot row is the cheap authoritative read; an exact-name filter still
        // comes back as a page, so pick the exact match rather than the first row.
        ResourceViews.QueueView row = queues.queues(clusterId, ResourceQuery.of(queue, 1, 50, null)).data().stream()
                .filter(q -> queue.equals(q.queueName()))
                .findFirst()
                .orElseThrow(() -> new io.github.sudoitir.artemisstudio.service.NotFoundException("queue", queue));

        List<String> findings = new ArrayList<>();
        if (row.totalConsumerCount() == 0 && row.totalMessageCount() > 0) {
            findings.add("No consumers are attached and " + row.totalMessageCount()
                    + " messages are queued — nothing is draining this queue.");
        }
        if (row.nodesPresent() < row.nodesTotal()) {
            findings.add("The queue exists on " + row.nodesPresent() + " of " + row.nodesTotal()
                    + " nodes; the numbers here cover only the nodes reporting it.");
        }
        if (row.totalScheduledCount() > 0) {
            findings.add(row.totalScheduledCount() + " messages are scheduled for later delivery "
                    + "and are counted in the depth.");
        }

        // messageCount over the last hour answers "is this growing or draining", which
        // is the question a depth number alone cannot.
        String trend = trend(clusterId, queue);

        // A DLQ relationship is a naming convention, not broker metadata: say what was
        // observed rather than asserting a link the broker never declared.
        String dlq = queue.startsWith("DLQ") || queue.contains(".DLQ") || queue.endsWith(".dlq")
                ? "This queue looks like a dead-letter queue by name."
                : null;

        List<McpViews.ActivityRow> recent =
                brokerEvents.page(clusterId, null, null, row.address(), null, null, 1, 10).data().stream()
                        .map(McpDiagnosticTools::toActivity)
                        .toList();

        return new McpViews.QueueDiagnosis(
                clusterId,
                queue,
                row.address(),
                row.totalMessageCount(),
                row.totalConsumerCount(),
                row.totalDeliveringCount(),
                row.totalScheduledCount(),
                false,
                trend,
                slowConsumerVerdict(row),
                dlq,
                findings,
                recent);
    }

    /** Growing, draining or flat over the last hour, from the metric cache — no broker call. */
    private String trend(UUID clusterId, String queue) {
        try {
            Instant to = Instant.now();
            var response = metrics.query(
                    clusterId, List.of("messageCount"), "QUEUE", queue, to.minus(Duration.ofHours(1)), to, null);
            if (response.series().isEmpty() || response.series().get(0).points().size() < 2) {
                return "unknown (not enough samples yet)";
            }
            var points = response.series().get(0).points();
            double first = points.get(0).value();
            double last = points.get(points.size() - 1).value();
            if (last > first * 1.1) {
                return "growing (" + (long) first + " -> " + (long) last + " over 1h)";
            }
            if (last < first * 0.9) {
                return "draining (" + (long) first + " -> " + (long) last + " over 1h)";
            }
            return "flat (~" + (long) last + " over 1h)";
        } catch (IllegalArgumentException e) {
            return "unknown";
        }
    }

    /**
     * The broker's own view wins over an inferred one (ADR-0044). Studio has no
     * per-queue slow-consumer flag in the snapshot, so this reports what can be seen
     * and does not manufacture a verdict the broker did not give.
     */
    private static String slowConsumerVerdict(ResourceViews.QueueView row) {
        if (row.totalConsumerCount() == 0) {
            return "not applicable — no consumers attached";
        }
        if (row.totalDeliveringCount() > 0 && row.totalMessageCount() > row.totalDeliveringCount() * 10) {
            return "possible — depth is far above what is in flight; check "
                    + "slow-consumer-policy on the address and cluster_health for the broker's own verdict";
        }
        return "none observed";
    }

    // ---- config_diff ------------------------------------------------------

    @McpTool(
            name = "config_diff",
            description = "Classified config differences between two nodes. Omit nodeB for the first two.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult configDiff(
            @McpToolParam(required = true) String clusterId,
            @McpToolParam(description = "First node id", required = false) String nodeA,
            @McpToolParam(description = "Second node id", required = false) String nodeB) {
        UUID id = McpArgs.uuid("clusterId", clusterId);
        UUID a = McpArgs.optionalUuid("nodeA", nodeA);
        UUID b = McpArgs.optionalUuid("nodeB", nodeB);
        return McpErrors.guard(() -> diff(id, a, b));
    }

    private McpViews.ConfigDiff diff(UUID clusterId, UUID nodeA, UUID nodeB) {
        // A model asking "do these nodes agree" rarely has node ids to hand, so the
        // pair defaults to the cluster's first two rather than making it ask twice.
        if (nodeA == null || nodeB == null) {
            var nodes = nodeRepo.findByClusterIdOrderByNameAsc(clusterId);
            if (nodes.size() < 2) {
                throw new io.github.sudoitir.artemisstudio.service.ConflictException(
                        "single-node-cluster",
                        "This cluster has fewer than two nodes, so there is nothing to compare.");
            }
            nodeA = nodeA != null ? nodeA : nodes.get(0).getId();
            UUID finalA = nodeA;
            nodeB = nodeB != null
                    ? nodeB
                    : nodes.stream()
                            .map(n -> n.getId())
                            .filter(n -> !n.equals(finalA))
                            .findFirst()
                            .orElseThrow();
        }
        ConfigViews.ConfigDiffView view = configDiff.compare(clusterId, nodeA, nodeB);
        List<McpViews.ConfigDifference> items = new ArrayList<>();
        for (ConfigViews.ConfigSectionView section : view.sections()) {
            for (ConfigViews.ConfigEntryView entry : section.entries()) {
                // Only the drifting keys: a model does not need the hundreds that agree,
                // and shipping them is the single largest response-size risk in this tool.
                if (entry.drift()) {
                    items.add(new McpViews.ConfigDifference(
                            section.section() + "/" + entry.key(),
                            entry.classification(),
                            entry.left(),
                            entry.right()));
                }
            }
        }
        return new McpViews.ConfigDiff(
                clusterId, view.left().nodeName(), view.right().nodeName(), view.driftCount(), items);
    }

    // ---- browse_messages --------------------------------------------------

    @McpTool(
            name = "browse_messages",
            description = "A capped page of message headers. Bodies come from message_body.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult browseMessages(
            @McpToolParam(required = true) String clusterId,
            @McpToolParam(required = true) String queue,
            @McpToolParam(description = "e.g. JMSPriority > 5", required = false) String filter,
            @McpToolParam(description = "Max headers; capped server-side", required = false) Integer limit) {
        UUID id = McpArgs.uuid("clusterId", clusterId);
        String q = McpArgs.required("queue", queue);
        int capped = props.mcp().clamp(limit);
        return McpErrors.guard(() -> headers(id, q, filter, capped));
    }

    private McpViews.Page<McpViews.MessageHeader> headers(UUID clusterId, String queue, String filter, int limit) {
        MessageViews.MessagePageView page = messages.browse(clusterId, queue, null, filter, 1, limit + 1);
        List<McpViews.MessageHeader> rows = page.data().stream()
                .map(m -> new McpViews.MessageHeader(
                        String.valueOf(m.messageId()),
                        queue,
                        page.node().toString(),
                        m.timestamp() > 0 ? Instant.ofEpochMilli(m.timestamp()) : null,
                        m.size(),
                        String.valueOf(m.type()),
                        m.correlationId()))
                .toList();
        return McpViews.Page.of(rows, limit, "broker order");
    }

    @McpTool(
            name = "message_body",
            description = "The full body and properties of one message, by id.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult messageBody(
            @McpToolParam(required = true) String clusterId,
            @McpToolParam(required = true) String queue,
            @McpToolParam(required = true) String messageId) {
        UUID id = McpArgs.uuid("clusterId", clusterId);
        String q = McpArgs.required("queue", queue);
        long mid;
        try {
            mid = Long.parseLong(McpArgs.required("messageId", messageId));
        } catch (NumberFormatException e) {
            throw McpErrors.invalidParams("messageId must be the numeric id browse_messages returned.");
        }
        return McpErrors.guard(() -> messages.detail(id, q, mid, null, null));
    }

    // ---- trace_request_reply ---------------------------------------------

    private enum RrMode {
        FLOWS,
        STATS,
        EXPECTATIONS,
        /** Why there are no flows — the sampler's account and the ranked reasons. */
        DIAGNOSTICS
    }

    @McpTool(
            name = "trace_request_reply",
            description = "Request-reply tracing: flows, stats (latency, timeouts), expectations, or "
                    + "diagnostics (why there are no flows).",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult traceRequestReply(
            @McpToolParam(required = true) String clusterId,
            @McpToolParam(description = "Default flows", required = false) String mode,
            @McpToolParam(description = "Request address filter", required = false) String address,
            @McpToolParam(description = "e.g. 15m. Default 15m", required = false) String window,
            @McpToolParam(description = "Max rows", required = false) Integer limit) {
        UUID id = McpArgs.uuid("clusterId", clusterId);
        RrMode m = McpArgs.enumOf(RrMode.class, "mode", mode, RrMode.FLOWS);
        Duration w = parseWindow(window == null ? "15m" : window);
        int capped = props.mcp().clamp(limit);
        return McpErrors.guard(() -> switch (m) {
            case FLOWS -> flows(id, address, capped);
            case STATS -> rrMetrics.stats(id, w);
            case EXPECTATIONS -> requestReply.list(id);
            // Answers the question a model otherwise cannot: an empty flow list means
            // "nothing was sent", "nothing could be browsed", or "consumed faster than
            // the sampler ticks", and only this tells them apart.
            case DIAGNOSTICS -> requestReply.diagnostics(id);
        });
    }

    private McpViews.Page<McpViews.FlowRow> flows(UUID clusterId, String address, int limit) {
        RrViews.FlowPageView page = requestReply.flowPage(clusterId, null, address, null, null, null, 1, limit + 1);
        List<McpViews.FlowRow> rows = page.data().stream()
                .map(f -> new McpViews.FlowRow(
                        f.correlationId(),
                        f.requestAddress(),
                        f.replyDestination(),
                        f.requestedAt(),
                        f.latencyMs(),
                        f.state()))
                .toList();
        return McpViews.Page.of(rows, limit, "requestedAt desc");
    }

    // ---- activity_log -----------------------------------------------------

    private enum LogSource {
        BROKER_EVENTS,
        AUDIT
    }

    @McpTool(
            name = "activity_log",
            description = "Recent activity: broker_events from the broker, or audit from Studio.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult activityLog(
            @McpToolParam(required = true) String clusterId,
            @McpToolParam(description = "Default broker_events", required = false) String source,
            @McpToolParam(description = "Address or action filter", required = false) String filter,
            @McpToolParam(description = "Max rows", required = false) Integer limit) {
        UUID id = McpArgs.uuid("clusterId", clusterId);
        LogSource src = McpArgs.enumOf(LogSource.class, "source", source, LogSource.BROKER_EVENTS);
        int capped = props.mcp().clamp(limit);
        return McpErrors.guard(() -> activity(id, src, filter, capped));
    }

    private McpViews.Page<McpViews.ActivityRow> activity(UUID clusterId, LogSource source, String filter, int limit) {
        List<McpViews.ActivityRow> rows =
                switch (source) {
                    case BROKER_EVENTS ->
                        brokerEvents.page(clusterId, null, null, filter, null, null, 1, limit + 1).data().stream()
                                .map(McpDiagnosticTools::toActivity)
                                .toList();
                    case AUDIT ->
                        auditLog.page(clusterId, null, filter, null, null, null, 1, limit + 1).data().stream()
                                .map(McpDiagnosticTools::toActivity)
                                .toList();
                };
        return McpViews.Page.of(rows, limit, "most recent first");
    }

    private static McpViews.ActivityRow toActivity(EventViews.BrokerEventView e) {
        return new McpViews.ActivityRow(
                e.occurredAt(),
                e.type(),
                e.username(),
                e.address() != null ? e.address() : e.routingName(),
                "observed",
                e.consumerName() != null ? "consumer=" + e.consumerName() : null);
    }

    private static McpViews.ActivityRow toActivity(AuditViews.AuditEventView e) {
        return new McpViews.ActivityRow(
                e.ts(),
                e.action(),
                e.username(),
                e.targetName(),
                // A dry run that "succeeded" changed nothing; saying so keeps a model from
                // reading the audit trail as a record of things that actually happened.
                e.dryRun() ? e.outcome() + " (dry run)" : e.outcome(),
                e.error() != null ? e.error() : (e.affectedCount() != null ? "affected=" + e.affectedCount() : null));
    }
}
