package io.github.sudoitir.artemisstudio.mcp;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The MCP surface's own result projections (ADR-0045).
 *
 * <p>These deliberately do <em>not</em> reuse {@code web/dto}: those records exist
 * to feed a UI that renders every field, are shaped by the MapStruct mappers for
 * that purpose, and carry {@code @Schema} annotations describing an HTTP contract.
 * Reusing them would silently couple the MCP wire format to UI needs and bill a
 * model for fields it has no use for.
 *
 * <p>The shape rules here are the whole point: flat, scalar-heavy, ids rather than
 * embedded object graphs, and no field a model would not act on.
 */
public final class McpViews {

    private McpViews() {}

    /** One broker endpoint's live state, as {@code diagnose} reports it. */
    public record NodeHealth(
            String node, String haRole, boolean live, boolean manageable, String artemisNodeId, Instant lastSeenAt) {}

    /** One alert currently firing. Rule name over rule id — the model reads it, it does not join on it. */
    public record FiringAlert(String rule, String subject, String severity, Double value, Instant since) {}

    /**
     * {@code diagnose} with no queue — the five-second answer. Everything a triage step
     * needs and nothing it does not: who is live, is the cluster split-brained, is
     * replication behind, what is firing.
     *
     * @param alertsVisible false when this key holds no {@code alert:read} on the
     *     cluster, so an empty {@code firingAlerts} is not read as "nothing wrong"
     *     (non-negotiable #5: never a silently missing capability)
     */
    public record ClusterHealth(
            UUID clusterId,
            String cluster,
            String level,
            String splitBrain,
            boolean replicationBehind,
            List<String> liveNodes,
            List<NodeHealth> nodes,
            List<String> notes,
            boolean alertsVisible,
            List<FiringAlert> firingAlerts,
            /**
             * When this answer was assembled. A model has no other way to tell a
             * fresh verdict from one about a cluster last reachable an hour ago.
             */
            Instant asOf,
            ClockVerdict clock) {}

    /**
     * Whether the clocks involved can be trusted (ADR-0053). Result shape only — it
     * costs nothing on {@code tools/list}, and without it a model reasoning about a
     * timeout or a latency has no way to know the numbers were measured against a
     * clock that disagrees.
     *
     * @param verdict UNKNOWN, IN_AGREEMENT, BROKER_SKEWED, or STUDIO_SUSPECT — the
     *     last meaning every node disagrees the same way, so Studio's own host is
     *     the thing to check
     */
    public record ClockVerdict(
            String verdict, Long worstOffsetMs, Long uncertaintyMs, List<String> skewedNodes, Instant measuredAt) {}

    /**
     * One close's result (ADR-0057).
     *
     * @param alreadyGone the target was not there to close — a success, and the
     *     field that stops a model retrying onto whatever now holds that identifier
     * @param label the client id or remote address that was closed, which stays
     *     meaningful after the connection id stops resolving
     * @param messagesInTransit in-flight messages returned to their queues with an
     *     increased delivery count; null when the broker did not report it
     */
    public record CloseOutcome(
            String kind,
            String subject,
            boolean dryRun,
            boolean alreadyGone,
            String label,
            Long sessionCount,
            Long consumerCount,
            Long messagesInTransit,
            long cap,
            boolean overCap,
            List<LifecycleNode> nodes,
            String message) {}

    /** A cluster the calling key can see, as {@code studio://clusters} lists it. */
    public record ClusterEntry(UUID clusterId, String cluster, String level, int nodeCount, UUID environmentId) {}

    /** One grant the calling key holds, as {@code studio://permissions} lists it. */
    public record GrantEntry(String scope, UUID scopeId, List<String> permissions) {}

    /** {@code studio://permissions} — what <em>this key</em> can do, not what the product supports. */
    public record TokenPermissions(String user, String tokenName, List<GrantEntry> grants) {}

    /** One capability a cluster's connection either has or does not, with the remedy when it does not. */
    public record CapabilityEntry(String capability, String status, String reason, String brokerXmlSnippet) {}

    /** A generic capped page. {@code truncated} is stated, never inferred from {@code items.size()}. */
    public record Page<T>(List<T> items, int returned, boolean truncated, String orderedBy) {

        public static <T> Page<T> of(List<T> all, int limit, String orderedBy) {
            boolean truncated = all.size() > limit;
            List<T> items = truncated ? all.subList(0, limit) : all;
            return new Page<>(List.copyOf(items), items.size(), truncated, orderedBy);
        }
    }

    /** A row of {@code list_resources}, flattened across every {@code ResourceKind}. */
    public record ResourceRow(String kind, String name, String node, String detail, Long count) {}

    /** One bucket of {@code metric_series}. */
    public record MetricPoint(Instant at, double value) {}

    /** {@code metric_series} result. */
    public record MetricSeries(
            UUID clusterId, String metric, String subject, String window, List<MetricPoint> points) {}

    /** {@code diagnose} with a queue — one queue, end to end, replacing four screens. */
    public record QueueDiagnosis(
            UUID clusterId,
            String queue,
            String address,
            long messageCount,
            long consumerCount,
            long messagesAdded,
            long messagesAcknowledged,
            boolean paused,
            String depthTrend,
            String slowConsumerVerdict,
            String dlq,
            List<String> findings,
            List<ActivityRow> recentEvents) {}

    /** A log-shaped row, shared by {@code activity_log} over both sources. */
    public record ActivityRow(Instant at, String kind, String actor, String subject, String outcome, String detail) {}

    /** One classified difference between two nodes' configuration (ADR-0043). */
    public record ConfigDifference(String pointer, String classification, String nodeA, String nodeB) {}

    /** {@code config_diff} result. */
    public record ConfigDiff(
            UUID clusterId, String nodeA, String nodeB, int differences, List<ConfigDifference> items) {}

    /** A message header row from {@code browse_messages}; bodies are a separate, explicit call. */
    public record MessageHeader(
            String messageId,
            String queue,
            String node,
            Instant timestamp,
            Long size,
            String type,
            String correlationId) {}

    /** The outcome of a guarded mutation, dry-run or real. */
    public record MutationOutcome(
            String action,
            String subject,
            boolean dryRun,
            long affected,
            Long cap,
            boolean overCap,
            String node,
            String message) {}

    /**
     * The result of a cluster-wide lifecycle command (ADR-0049). The per-node list
     * is returned in full: an agent that cannot tell a fully applied command from a
     * partially applied one will confidently report the wrong thing to a human.
     */
    public record LifecycleOutcomeSummary(
            String action,
            String subject,
            boolean dryRun,
            boolean partial,
            long totalAffected,
            Long cap,
            boolean overCap,
            List<LifecycleNode> nodes,
            String message) {}

    /** One node's share of a lifecycle command. */
    public record LifecycleNode(String node, String status, Long affected, String error) {}

    /** An alert rule, flattened — ids as ids, no channel objects. */
    public record AlertRuleSummary(
            UUID ruleId,
            String name,
            String kind,
            String metric,
            String comparator,
            Double threshold,
            String stateCondition,
            int forSeconds,
            String severity,
            String scope,
            boolean enabled) {}

    /** One operator-tunable setting: its effective value and whether it is an override. */
    public record SettingEntry(String key, String value, boolean overridden) {}

    /** One request-reply flow. */
    public record FlowRow(
            String correlationId,
            String requestQueue,
            String replyQueue,
            Instant startedAt,
            Long latencyMs,
            String state) {}

    // ---- broker_config ----------------------------------------------------

    /** A cluster's declaration, as a model reads it: the header and the document itself. */
    public record ConfigDeclaration(
            boolean declared,
            int revision,
            String applyMode,
            String updatedBy,
            Instant updatedAt,
            Object document,
            List<ConfigNodeState> nodes) {}

    /** One node's last drift evaluation. */
    public record ConfigNodeState(
            String node,
            boolean live,
            String state,
            String detail,
            Instant evaluatedAt,
            List<ConfigFinding> findings) {}

    /** One thing a node does differently from the declaration. */
    public record ConfigFinding(String kind, String section, String key, String detail) {}

    /** One past apply. */
    public record ConfigApply(
            long id,
            Instant startedAt,
            boolean dryRun,
            String outcome,
            String summary,
            String actor,
            Long auditEventId) {}

    /**
     * What an apply — dry or real — produced. {@code acknowledge} lists exactly the
     * hazard identifiers a real run must carry; {@code message} is the one sentence
     * an agent should relay.
     */
    public record ConfigApplyOutcome(
            boolean dryRun,
            String outcome,
            int revision,
            String planHash,
            String canary,
            int stepCount,
            int stepCap,
            boolean overCap,
            List<ConfigHazard> hazards,
            List<String> acknowledge,
            List<ConfigNodeApply> nodes,
            String message) {}

    public record ConfigHazard(String id, String hazardClass, String node, String message) {}

    public record ConfigNodeApply(String node, boolean canary, String note, List<ConfigStep> steps) {}

    public record ConfigStep(
            String id, String op, String section, String key, String status, String verified, String error) {}
}
