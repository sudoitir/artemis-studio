package io.github.sudoitir.artemisstudio.mcp;

import io.github.sudoitir.artemisstudio.service.LifecycleKind;
import io.github.sudoitir.artemisstudio.service.MessageAction;
import io.github.sudoitir.artemisstudio.service.ResourceKind;
import java.util.List;
import java.util.stream.Stream;

/**
 * What the tool schemas leave out, served on demand as {@code studio://tools}
 * (ADR-0050).
 *
 * <p>The MCP tool listing is a fixed cost paid on every conversation, before the
 * model has asked for anything. Enum members and JSON body shapes are most of that
 * cost and are needed by one tool, after the model has already chosen it — so the
 * listing names this resource and a model that needs the detail fetches it once.
 *
 * <p>This is documentation, not validation. Every discriminator is still checked
 * server-side and every rejection names the values it would have accepted, so a
 * model that never reads this is inconvenienced, never wrong.
 */
final class McpToolDetail {

    private McpToolDetail() {}

    /**
     * @param tool the tool the detail belongs to
     * @param parameter the parameter within it
     * @param values the values it accepts, when it is a discriminator
     * @param shape the JSON body shape, when it takes one
     */
    record ToolParameterDetail(String tool, String parameter, List<String> values, String shape, String note) {}

    static List<ToolParameterDetail> entries() {
        return List.of(
                new ToolParameterDetail(
                        "queue_lifecycle",
                        "kind",
                        names(LifecycleKind.values()),
                        null,
                        "A command targets the cluster and fans out to every live node. The result is a "
                                + "per-node list: a node already in the requested state reports ALREADY, and one "
                                + "that was not live reports SKIPPED_NOT_LIVE rather than a failure. Re-running "
                                + "after a partial application converges."),
                new ToolParameterDetail(
                        "queue_lifecycle",
                        "config",
                        null,
                        "{ address, routingType (ANYCAST|MULTICAST), durable, filter, maxConsumers, "
                                + "purgeOnNoConsumers, exclusive, nonDestructive, ringSize }",
                        "create_queue needs at least address and routingType. On update_queue only the fields "
                                + "you send change; address, routingType, name and durable cannot change on a "
                                + "live queue. create_address reads routingType only."),
                new ToolParameterDetail(
                        "queue_action", "action", names(MessageAction.values()), null, "purge is also accepted."),
                new ToolParameterDetail(
                        "alert_rule",
                        "rule",
                        null,
                        "{ name, kind, metric, comparator, threshold, stateCondition, forSeconds, severity, "
                                + "scope, enabled }",
                        "Required for create; a partial body is merged on update."),
                new ToolParameterDetail("alert_rule", "op", List.of("list", "create", "update", "delete"), null, null),
                new ToolParameterDetail("list_resources", "kind", names(ResourceKind.values()), null, null),
                new ToolParameterDetail(
                        "metric_series",
                        "metric",
                        List.of("messageCount", "consumerCount", "messagesAdded", "messagesAcked"),
                        null,
                        null),
                new ToolParameterDetail(
                        "trace_request_reply",
                        "mode",
                        List.of("flows", "stats", "expectations", "diagnostics"),
                        null,
                        null),
                new ToolParameterDetail("activity_log", "source", List.of("broker_events", "audit"), null, null),
                new ToolParameterDetail("studio_setting", "op", List.of("get", "set"), null, null));
    }

    private static List<String> names(Enum<?>[] values) {
        return Stream.of(values).map(e -> e.name().toLowerCase()).toList();
    }
}
