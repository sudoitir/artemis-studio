package io.github.sudoitir.artemisstudio.mcp;

import io.github.sudoitir.artemisstudio.service.AlertRuleService;
import io.github.sudoitir.artemisstudio.service.Attempt;
import io.github.sudoitir.artemisstudio.service.MessageAction;
import io.github.sudoitir.artemisstudio.service.MessageService;
import io.github.sudoitir.artemisstudio.service.MessageService.Outcome;
import io.github.sudoitir.artemisstudio.service.NotFoundException;
import io.github.sudoitir.artemisstudio.service.SettingsService;
import io.github.sudoitir.artemisstudio.web.dto.AlertViews.AlertRuleRequest;
import io.github.sudoitir.artemisstudio.web.dto.AlertViews.AlertRuleView;
import io.github.sudoitir.artemisstudio.web.dto.MessageRequests.MessageActionRequest;
import io.github.sudoitir.artemisstudio.web.dto.MessageRequests.SendMessageRequest;
import io.github.sudoitir.artemisstudio.web.dto.SettingsViews.SettingValue;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * The mutating half of the MCP surface (ADR-0045). Everything here goes through
 * the same services the REST layer calls, so authorization, the bulk cap and the
 * audit row are the existing ones — this class adds exactly one thing on top:
 * the model-facing safety contract.
 *
 * <p>That contract is two independent gates, and conflating them is the mistake
 * this file is written to prevent (design D7):
 *
 * <ul>
 *   <li>{@code dryRun} defaults to <b>true</b>, and turning it off on a
 *       destructive action additionally requires {@code confirm} to equal the
 *       subject's name. This gate exists because the caller is a model: it stops
 *       an inferred name or a hallucinated queue from becoming a real purge.
 *   <li>{@code override} is the pre-existing ADR-0022 bulk cap escape, and it
 *       answers a different question — "yes, this many messages really is
 *       intended". It defaults to {@code false} and is <b>never</b> satisfied by
 *       {@code confirm}; a caller that meant to purge one queue has said nothing
 *       about whether 400 000 messages is a surprise.
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class McpTuningTools {

    private final MessageService messages;
    private final AlertRuleService alertRules;
    private final SettingsService settings;

    /** The queue-scoped mutations, as one tool. */
    public enum QueueActionKind {
        MOVE,
        RETRY,
        DELETE,
        EXPIRE,
        PURGE
    }

    public enum RuleOp {
        LIST,
        CREATE,
        UPDATE,
        DELETE
    }

    public enum SettingOp {
        GET,
        SET
    }

    /** The {@code alert_rule} body, named once so the schema pays for it once. */
    private static final String RULE_SHAPE = "JSON: name, kind, metric, comparator, threshold, "
            + "stateCondition, forSeconds, severity, scope, enabled";

    /** What {@code rule} deserialises to. Every field optional here; required-ness is checked by op. */
    public record RuleBody(
            String name,
            String kind,
            String metric,
            String comparator,
            Double threshold,
            String stateCondition,
            Integer forSeconds,
            String severity,
            String scope,
            Boolean enabled) {}

    // ---- queue_action -----------------------------------------------------

    @McpTool(
            name = "queue_action",
            description =
                    "Move, retry, delete, expire or purge messages on a queue. " + "Select with messageIds or filter.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = false,
                            destructiveHint = true,
                            idempotentHint = false,
                            openWorldHint = false))
    public McpSchema.CallToolResult queueAction(
            @McpToolParam(required = true) String clusterId,
            @McpToolParam(required = true) String queue,
            @McpToolParam(description = "move, retry, delete, expire or purge", required = true) String action,
            @McpToolParam(required = false) String messageIds,
            @McpToolParam(required = false) String filter,
            @McpToolParam(required = false) String targetQueue,
            @McpToolParam(description = "Default true", required = false) Boolean dryRun,
            @McpToolParam(description = "The queue name", required = false) String confirm,
            @McpToolParam(required = false) Boolean override) {
        UUID id = McpArgs.uuid("clusterId", clusterId);
        String q = McpArgs.required("queue", queue);
        QueueActionKind kind = McpArgs.enumOf(QueueActionKind.class, "action", action, null);
        boolean dry = McpArgs.flag(dryRun, true);
        boolean over = McpArgs.flag(override, false);
        if (!dry) {
            requireConfirmation(q, confirm);
        }
        return McpErrors.guard(() -> {
            Attempt<Outcome> attempt = kind == QueueActionKind.PURGE
                    ? messages.purge(id, q, null, dry, over)
                    : messages.execute(
                            id,
                            q,
                            null,
                            MessageAction.valueOf(kind.name()),
                            new MessageActionRequest(parseIds(messageIds), filter, targetQueue),
                            dry,
                            over);
            return outcome(kind.name().toLowerCase(Locale.ROOT), q, dry, attempt);
        });
    }

    // ---- send_message -----------------------------------------------------

    @McpTool(
            name = "send_message",
            description = "Enqueue one message. Dry-runs by default; adds, never removes.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = false,
                            // Additive: nothing existing is lost, so a host must not
                            // gate this behind the same warning as a purge.
                            destructiveHint = false,
                            idempotentHint = false,
                            openWorldHint = false))
    public McpSchema.CallToolResult sendMessage(
            @McpToolParam(required = true) String clusterId,
            @McpToolParam(required = true) String queue,
            @McpToolParam(required = true) String body,
            @McpToolParam(description = "3 text, 4 bytes. Default 3", required = false) Integer type,
            @McpToolParam(description = "Default true", required = false) Boolean durable,
            @McpToolParam(description = "Default true", required = false) Boolean dryRun) {
        UUID id = McpArgs.uuid("clusterId", clusterId);
        String q = McpArgs.required("queue", queue);
        String payload = McpArgs.required("body", body);
        boolean dry = McpArgs.flag(dryRun, true);
        return McpErrors.guard(() -> {
            Attempt<Outcome> attempt = messages.send(
                    id,
                    q,
                    null,
                    new SendMessageRequest(
                            type == null ? 3 : type, McpArgs.flag(durable, true), payload, false, Map.of(), Map.of()),
                    dry);
            return outcome("send", q, dry, attempt);
        });
    }

    // ---- alert_rule -------------------------------------------------------

    @McpTool(
            name = "alert_rule",
            description = "List, create, update or delete alert rules.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = false,
                            destructiveHint = true,
                            idempotentHint = false,
                            openWorldHint = false))
    public McpSchema.CallToolResult alertRule(
            @McpToolParam(required = true) String clusterId,
            @McpToolParam(description = "Default list", required = false) String op,
            @McpToolParam(description = "For update, delete", required = false) String ruleId,
            @McpToolParam(description = RULE_SHAPE, required = false) String rule,
            @McpToolParam(description = "The rule name, for delete", required = false) String confirm) {
        UUID id = McpArgs.uuid("clusterId", clusterId);
        RuleOp operation = McpArgs.enumOf(RuleOp.class, "op", op, RuleOp.LIST);
        UUID ruleUuid = McpArgs.optionalUuid("ruleId", ruleId);
        if ((operation == RuleOp.UPDATE || operation == RuleOp.DELETE) && ruleUuid == null) {
            throw McpErrors.invalidParams(
                    "ruleId is required for " + operation.name().toLowerCase(Locale.ROOT) + ".");
        }
        return McpErrors.guard(() -> switch (operation) {
            case LIST -> alertRules.list(id).stream().map(McpTuningTools::rule).toList();
            case CREATE -> rule(alertRules.create(id, ruleRequest(rule)));
            case UPDATE -> rule(alertRules.update(id, ruleUuid, ruleRequest(rule)));
            case DELETE -> {
                // A delete is irreversible and the model chose the id, so the same
                // confirm gate as queue_action applies — by the rule's own name.
                AlertRuleView existing = alertRules.list(id).stream()
                        .filter(r -> r.id().equals(ruleUuid))
                        .findFirst()
                        .orElseThrow(() -> new NotFoundException("alert rule", ruleUuid));
                requireConfirmation(existing.name(), confirm);
                alertRules.delete(id, ruleUuid);
                yield new McpViews.MutationOutcome(
                        "alert_rule.delete", existing.name(), false, 1, null, false, null, "Rule deleted.");
            }
        });
    }

    // ---- studio_setting ---------------------------------------------------

    @McpTool(
            name = "studio_setting",
            description =
                    "Read or change an operational setting: scrape cadence, rate limit, " + "retention, bulk cap.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult studioSetting(
            @McpToolParam(description = "Default get", required = false) String op,
            @McpToolParam(description = "Omit on get for all", required = false) String key,
            @McpToolParam(description = "Required for set", required = false) String value) {
        SettingOp operation = McpArgs.enumOf(SettingOp.class, "op", op, SettingOp.GET);
        return McpErrors.guard(() -> {
            Map<String, SettingValue> effective = settings.effective();
            if (operation == SettingOp.SET) {
                String k = McpArgs.required("key", key);
                settings.put(k, McpArgs.required("value", value));
                effective = settings.effective();
                return entry(k, effective.get(k));
            }
            if (key != null && !key.isBlank()) {
                return entry(key.trim(), effective.get(key.trim()));
            }
            List<McpViews.SettingEntry> all = new ArrayList<>();
            effective.forEach((k, v) -> all.add(entry(k, v)));
            return all;
        });
    }

    // ---- helpers ----------------------------------------------------------

    /**
     * The model-facing gate. It is deliberately an exact match on the subject's
     * own name: anything looser (a yes, a boolean) is something a model will
     * produce without having read the name it is about to destroy.
     */
    private static void requireConfirmation(String subject, String confirm) {
        if (!subject.equals(confirm)) {
            throw McpErrors.invalidParams("A real run needs confirm to be exactly \"" + subject
                    + "\". Re-run with dryRun=true to see " + "what it would affect first.");
        }
    }

    private static List<Long> parseIds(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                ids.add(Long.parseLong(trimmed));
            } catch (NumberFormatException e) {
                throw McpErrors.invalidParams("messageIds must be comma-separated numbers; got \"" + trimmed + "\".");
            }
        }
        return ids;
    }

    /** A broker that could not be reached is a failed operation, not a failed call. */
    private static McpViews.MutationOutcome outcome(
            String action, String subject, boolean dryRun, Attempt<Outcome> attempt) {
        return switch (attempt) {
            case Attempt.Failed<Outcome> f ->
                throw new io.github.sudoitir.artemisstudio.broker.BrokerConnectionException(f.kind(), f.detail());
            case Attempt.Ok<Outcome> ok ->
                switch (ok.value()) {
                    case Outcome.DryRun d ->
                        new McpViews.MutationOutcome(
                                action,
                                subject,
                                true,
                                d.count(),
                                d.cap(),
                                d.overCap(),
                                String.valueOf(d.node()),
                                d.overCap()
                                        ? "Nothing was changed. A real run is over the bulk cap and would need override=true."
                                        : "Nothing was changed. Re-run with dryRun=false and confirm=\"" + subject
                                                + "\".");
                    case Outcome.Affected a ->
                        new McpViews.MutationOutcome(
                                action, subject, false, a.count(), null, false, String.valueOf(a.node()), "Applied.");
                };
        };
    }

    private static McpViews.AlertRuleSummary rule(AlertRuleView v) {
        return new McpViews.AlertRuleSummary(
                v.id(),
                v.name(),
                v.kind(),
                v.metric(),
                v.comparator(),
                v.threshold(),
                v.stateCondition(),
                v.forSeconds(),
                v.severity(),
                v.scope(),
                v.enabled());
    }

    /**
     * The rule body arrives as one JSON argument rather than ten flat ones. Ten
     * scalars would cost more of the {@code tools/list} budget than the whole read
     * surface's filters put together (ADR-0045), and unlike an id or a
     * discriminator these fields are only ever supplied together, copied from a
     * rule the model has just listed.
     */
    private static AlertRuleRequest ruleRequest(String raw) {
        RuleBody body = McpErrors.parse("rule", McpArgs.required("rule", raw), RuleBody.class);
        return new AlertRuleRequest(
                McpArgs.required("rule.name", body.name()),
                McpArgs.required("rule.kind", body.kind()),
                body.metric(),
                body.comparator(),
                body.threshold(),
                body.stateCondition(),
                body.forSeconds() == null ? 0 : body.forSeconds(),
                McpArgs.required("rule.severity", body.severity()),
                body.scope(),
                McpArgs.flag(body.enabled(), true),
                List.of());
    }

    private static McpViews.SettingEntry entry(String key, SettingValue value) {
        if (value == null) {
            throw McpErrors.invalidParams("Unknown setting key: " + key + ".");
        }
        return new McpViews.SettingEntry(key, value.value(), value.overridden());
    }
}
