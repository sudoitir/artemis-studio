package io.github.sudoitir.artemisstudio.feature.triage.mcp;

import io.github.sudoitir.artemisstudio.feature.alerting.AlertPermissions;
import io.github.sudoitir.artemisstudio.feature.alerting.AlertService;
import io.github.sudoitir.artemisstudio.feature.events.BrokerEventService;
import io.github.sudoitir.artemisstudio.feature.events.web.EventViews;
import io.github.sudoitir.artemisstudio.feature.metrics.MetricQueryService;
import io.github.sudoitir.artemisstudio.feature.resources.CrossNodeAggregator;
import io.github.sudoitir.artemisstudio.feature.resources.web.ResourceViews;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditQueryService;
import io.github.sudoitir.artemisstudio.kernel.audit.web.AuditViews;
import io.github.sudoitir.artemisstudio.kernel.core.ResourceQuery;
import io.github.sudoitir.artemisstudio.kernel.security.PermissionResolver;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterService;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.artemisstudio.platform.clusters.web.ClusterViews;
import io.github.sudoitir.artemisstudio.platform.mcp.McpArgs;
import io.github.sudoitir.artemisstudio.platform.mcp.McpErrors;
import io.github.sudoitir.artemisstudio.platform.mcp.McpProperties;
import io.github.sudoitir.artemisstudio.platform.mcp.McpViews;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The {@code diagnose} and {@code activity_log} MCP tools: triage questions that read across
 * clusters, resources, metrics, events, alerts and the audit trail. *
 * <p>Reads only: nothing here needs the dry-run/confirm contract, which is the review
 * boundary ADR-0045 draws between read and mutating tool classes. Every method is a thin
 * adapter over the services the REST layer already uses.
 */
@Component
@RequiredArgsConstructor
public class TriageMcpTools {

    private final McpProperties props;
    private final ClusterService clusters;
    private final CrossNodeAggregator queues;
    private final MetricQueryService metrics;
    private final BrokerEventService brokerEvents;
    private final AuditQueryService auditLog;
    private final ClusterRepository clusterRepo;
    private final ObjectProvider<AlertService> alerts;
    private final PermissionResolver perm;
    private final io.github.sudoitir.artemisstudio.platform.broker.ClockOffsetService clocks;

    /**
     * Cluster health and single-queue diagnosis behind one optional argument
     * (ADR-0054).
     *
     * <p>They merge because they share both conditions the grouping rule requires:
     * one honest posture — neither mutates — and one target family, a cluster and
     * something in it, scoped by an optional name. That is the shape
     * {@code metric_series} already uses for the same reason.
     *
     * <p>It is also the pair a model most often has to choose between blind: "is
     * this cluster healthy" and "why is this queue backing up" are the same triage
     * step at two scopes, and making the scope an argument removes a selection
     * decision rather than adding one.
     */
    @McpTool(
            name = "diagnose",
            description = "Cluster health, or one queue end to end. Start here.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult diagnose(
            @McpToolParam(required = true) String clusterId, @McpToolParam(required = false) String queue) {
        UUID id = McpArgs.uuid("clusterId", clusterId);
        if (queue == null || queue.isBlank()) {
            return McpErrors.guard(() -> health(id));
        }
        String name = queue.trim();
        return McpErrors.guard(() -> diagnose(id, name));
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
                .map(io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.ClusterEntity::getName)
                .orElseThrow(
                        () -> new io.github.sudoitir.artemisstudio.kernel.core.NotFoundException("cluster", clusterId));

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
        // Alerting is an optional feature; with it disabled there are no alerts to show,
        // and alertsVisible=false says so rather than reporting an empty list as "quiet".
        AlertService alertService = alerts.getIfAvailable();
        boolean alertsVisible = alertService != null && perm.can(clusterId, AlertPermissions.ALERT_READ);
        List<McpViews.FiringAlert> firing = alertsVisible
                ? alertService.firingNow(clusterId).stream()
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
                        .map(io.github.sudoitir.artemisstudio.platform.broker.ClockOffsetService.NodeSkew::nodeName)
                        .toList(),
                assessment.at());
    }

    private McpViews.QueueDiagnosis diagnose(UUID clusterId, String queue) {
        // The snapshot row is the cheap authoritative read; an exact-name filter still
        // comes back as a page, so pick the exact match rather than the first row.
        ResourceViews.QueueView row = queues.queues(clusterId, ResourceQuery.of(queue, 1, 50, null)).data().stream()
                .filter(q -> queue.equals(q.queueName()))
                .findFirst()
                .orElseThrow(() -> new io.github.sudoitir.artemisstudio.kernel.core.NotFoundException("queue", queue));

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
                        .map(TriageMcpTools::toActivity)
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
                    + "slow-consumer-policy on the address and diagnose for the broker's own verdict";
        }
        return "none observed";
    }

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
            @McpToolParam(required = false) String source,
            @McpToolParam(required = false) String filter,
            @McpToolParam(required = false) Integer limit) {
        UUID id = McpArgs.uuid("clusterId", clusterId);
        LogSource src = McpArgs.enumOf(LogSource.class, "source", source, LogSource.BROKER_EVENTS);
        int capped = props.clamp(limit);
        return McpErrors.guard(() -> activity(id, src, filter, capped));
    }

    private McpViews.Page<McpViews.ActivityRow> activity(UUID clusterId, LogSource source, String filter, int limit) {
        List<McpViews.ActivityRow> rows =
                switch (source) {
                    case BROKER_EVENTS ->
                        brokerEvents.page(clusterId, null, null, filter, null, null, 1, limit + 1).data().stream()
                                .map(TriageMcpTools::toActivity)
                                .toList();
                    case AUDIT ->
                        auditLog.page(clusterId, null, filter, null, null, null, 1, limit + 1).data().stream()
                                .map(TriageMcpTools::toActivity)
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
