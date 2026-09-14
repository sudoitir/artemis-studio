package io.github.sudoitir.artemisstudio.feature.alerting.mcp;

import io.github.sudoitir.artemisstudio.feature.alerting.AlertRuleService;
import io.github.sudoitir.artemisstudio.feature.alerting.web.AlertViews.AlertRuleRequest;
import io.github.sudoitir.artemisstudio.feature.alerting.web.AlertViews.AlertRuleView;
import io.github.sudoitir.artemisstudio.kernel.core.NotFoundException;
import io.github.sudoitir.artemisstudio.platform.mcp.McpArgs;
import io.github.sudoitir.artemisstudio.platform.mcp.McpErrors;
import io.github.sudoitir.artemisstudio.platform.mcp.McpViews;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * The {@code alert_rule} MCP tool. *
 * <p>Mutating tools (ADR-0045). They go through the same services the REST layer calls, so
 * authorization, the bulk cap and the audit row are the existing ones; this class adds only
 * the model-facing gate: {@code dryRun} defaults to true, a real destructive run needs
 * {@code confirm} to equal the subject's name, and {@code override} is the separate bulk-cap
 * escape that {@code confirm} never satisfies.
 */
@Component
@RequiredArgsConstructor
public class AlertingMcpTools {

    private final AlertRuleService alertRules;

    public enum RuleOp {
        LIST,
        CREATE,
        UPDATE,
        DELETE
    }

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
            @McpToolParam(required = false) String op,
            @McpToolParam(required = false) String ruleId,
            @McpToolParam(required = false) String rule,
            @McpToolParam(required = false) String confirm) {
        UUID id = McpArgs.uuid("clusterId", clusterId);
        RuleOp operation = McpArgs.enumOf(RuleOp.class, "op", op, RuleOp.LIST);
        UUID ruleUuid = McpArgs.optionalUuid("ruleId", ruleId);
        if ((operation == RuleOp.UPDATE || operation == RuleOp.DELETE) && ruleUuid == null) {
            throw McpErrors.invalidParams(
                    "ruleId is required for " + operation.name().toLowerCase(Locale.ROOT) + ".");
        }
        return McpErrors.guard(() -> switch (operation) {
            case LIST ->
                alertRules.list(id).stream().map(AlertingMcpTools::rule).toList();
            case CREATE -> rule(alertRules.create(id, ruleRequest(rule)));
            case UPDATE -> rule(alertRules.update(id, ruleUuid, ruleRequest(rule)));
            case DELETE -> {
                // A delete is irreversible and the model chose the id, so the same
                // confirm gate as message_action applies — by the rule's own name.
                AlertRuleView existing = alertRules.list(id).stream()
                        .filter(r -> r.id().equals(ruleUuid))
                        .findFirst()
                        .orElseThrow(() -> new NotFoundException("alert rule", ruleUuid));
                McpArgs.confirm(existing.name(), confirm);
                alertRules.delete(id, ruleUuid);
                yield new McpViews.MutationOutcome(
                        "alert_rule.delete", existing.name(), false, 1, null, false, null, "Rule deleted.");
            }
        });
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
}
