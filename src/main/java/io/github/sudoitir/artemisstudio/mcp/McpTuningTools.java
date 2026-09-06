package io.github.sudoitir.artemisstudio.mcp;

import io.github.sudoitir.artemisstudio.service.AlertRuleService;
import io.github.sudoitir.artemisstudio.service.Attempt;
import io.github.sudoitir.artemisstudio.service.LifecycleKind;
import io.github.sudoitir.artemisstudio.service.LifecycleOutcome;
import io.github.sudoitir.artemisstudio.service.MessageAction;
import io.github.sudoitir.artemisstudio.service.MessageService;
import io.github.sudoitir.artemisstudio.service.MessageService.Outcome;
import io.github.sudoitir.artemisstudio.service.NotFoundException;
import io.github.sudoitir.artemisstudio.service.QueueLifecycleService;
import io.github.sudoitir.artemisstudio.service.SettingsService;
import io.github.sudoitir.artemisstudio.web.dto.AlertViews.AlertRuleRequest;
import io.github.sudoitir.artemisstudio.web.dto.AlertViews.AlertRuleView;
import io.github.sudoitir.artemisstudio.web.dto.LifecycleRequests.CreateAddressRequest;
import io.github.sudoitir.artemisstudio.web.dto.LifecycleRequests.CreateQueueRequest;
import io.github.sudoitir.artemisstudio.web.dto.LifecycleRequests.UpdateQueueRequest;
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
    private final QueueLifecycleService lifecycle;
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

    // ---- message_action ---------------------------------------------------

    /**
     * Named for its target, not its neighbour (ADR-0054). This was {@code
     * queue_action}, which sat beside {@code queue_lifecycle} and inverted the
     * distinction that matters: this acts on the messages <em>in</em> a queue, that
     * one acts on the queue itself. They stay separate tools — identical postures,
     * but thirteen operations across two disjoint argument clusters would make the
     * most dangerous schema in the product unusable without fetching detail first.
     */
    @McpTool(
            name = "message_action",
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
            @McpToolParam(required = true) String action,
            @McpToolParam(required = false) String messageIds,
            @McpToolParam(required = false) String filter,
            @McpToolParam(required = false) String targetQueue,
            @McpToolParam(required = false) Boolean dryRun,
            @McpToolParam(required = false) String confirm,
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

    // ---- queue_lifecycle --------------------------------------------------

    /** The queue configuration a create or update carries. Every field optional; checked per kind. */
    public record QueueConfigBody(
            String address,
            String routingType,
            Boolean durable,
            String filter,
            Integer maxConsumers,
            Boolean purgeOnNoConsumers,
            Boolean exclusive,
            Boolean nonDestructive,
            Long ringSize) {}

    /**
     * Queue and address lifecycle, as <b>one</b> tool discriminated by {@code kind}
     * rather than eight — the tool-count budget the MCP capability sets is a real
     * constraint, and eight near-identical verbs would spend it for nothing.
     *
     * <p>Delegates to {@link io.github.sudoitir.artemisstudio.service.QueueLifecycleService},
     * so the permission check, the bulk cap and the audit row are the same ones the
     * HTTP API gets. Nothing here implements authorization, capping or auditing of
     * its own.
     */
    @McpTool(
            name = "queue_lifecycle",
            description = "Queue and address lifecycle across a cluster's live nodes. Previews by default.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = false,
                            // Some kinds destroy a queue and its messages irrecoverably, and a
                            // host gates the whole tool on one flag — so the honest value for a
                            // tool that can delete is true.
                            destructiveHint = true,
                            // Every kind but the counter reset converges on re-run: that is what
                            // makes retrying after a partial fan-out safe (D2).
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult queueLifecycle(
            @McpToolParam(required = true) String clusterId,
            @McpToolParam(required = true) String kind,
            @McpToolParam(required = true) String name,
            @McpToolParam(required = false) String config,
            @McpToolParam(required = false) Boolean dryRun,
            @McpToolParam(required = false) String confirm,
            @McpToolParam(required = false) Boolean override) {
        UUID id = McpArgs.uuid("clusterId", clusterId);
        String subject = McpArgs.required("name", name);
        LifecycleKind op = McpArgs.enumOf(LifecycleKind.class, "kind", kind, null);
        boolean dry = McpArgs.flag(dryRun, true);
        boolean over = McpArgs.flag(override, false);
        if (!dry && op.destructive()) {
            requireConfirmation(subject, confirm);
        }
        QueueConfigBody body = parseConfig(config);
        return McpErrors.guard(() -> lifecycleOutcome(
                op,
                subject,
                dry,
                switch (op) {
                    case CREATE_QUEUE -> lifecycle.createQueue(id, createRequest(subject, body), dry);
                    case UPDATE_QUEUE -> lifecycle.updateQueue(id, subject, updateRequest(body), dry);
                    case DELETE_QUEUE -> lifecycle.deleteQueue(id, subject, dry, over);
                    case PAUSE_QUEUE -> lifecycle.setPaused(id, subject, true, dry);
                    case RESUME_QUEUE -> lifecycle.setPaused(id, subject, false, dry);
                    case RESET_QUEUE_COUNTER -> lifecycle.resetCounter(id, subject, dry);
                    case CREATE_ADDRESS -> lifecycle.createAddress(id, addressRequest(subject, body), dry);
                    case DELETE_ADDRESS -> lifecycle.deleteAddress(id, subject, dry);
                }));
    }

    private static QueueConfigBody parseConfig(String raw) {
        if (raw == null || raw.isBlank()) {
            return new QueueConfigBody(null, null, null, null, null, null, null, null, null);
        }
        return McpErrors.parse("config", raw, QueueConfigBody.class);
    }

    private static CreateQueueRequest createRequest(String name, QueueConfigBody body) {
        if (body.address() == null || body.routingType() == null) {
            throw McpErrors.invalidParams("create_queue needs config with at least address and routingType.");
        }
        return new CreateQueueRequest(
                body.address(),
                name,
                body.routingType(),
                body.durable(),
                body.filter(),
                body.maxConsumers(),
                body.purgeOnNoConsumers(),
                body.exclusive(),
                body.nonDestructive(),
                body.ringSize(),
                null);
    }

    private static UpdateQueueRequest updateRequest(QueueConfigBody body) {
        return new UpdateQueueRequest(
                body.filter(),
                body.maxConsumers(),
                body.purgeOnNoConsumers(),
                body.exclusive(),
                body.nonDestructive(),
                body.ringSize());
    }

    private static CreateAddressRequest addressRequest(String name, QueueConfigBody body) {
        return new CreateAddressRequest(name, body.routingType() == null ? "ANYCAST" : body.routingType());
    }

    private static McpViews.LifecycleOutcomeSummary lifecycleOutcome(
            LifecycleKind kind, String subject, boolean dryRun, Attempt<LifecycleOutcome> attempt) {
        LifecycleOutcome outcome =
                switch (attempt) {
                    case Attempt.Ok<LifecycleOutcome> ok -> ok.value();
                    case Attempt.Failed<LifecycleOutcome> f ->
                        throw new io.github.sudoitir.artemisstudio.broker.BrokerConnectionException(
                                f.kind(), f.detail());
                };
        List<McpViews.LifecycleNode> nodes = outcome.nodes().stream()
                .map(n -> new McpViews.LifecycleNode(n.nodeName(), n.status().name(), n.affected(), n.error()))
                .toList();
        boolean partial = !dryRun
                && nodes.stream().anyMatch(n -> "APPLIED".equals(n.status()) || "ALREADY".equals(n.status()))
                && nodes.stream().anyMatch(n -> "FAILED".equals(n.status()) || "SKIPPED_NOT_LIVE".equals(n.status()));
        return new McpViews.LifecycleOutcomeSummary(
                kind.name().toLowerCase(Locale.ROOT),
                subject,
                dryRun,
                partial,
                outcome.totalAffected(),
                outcome.cap(),
                outcome.overCap(),
                nodes,
                lifecycleMessage(kind, subject, dryRun, outcome, partial));
    }

    private static String lifecycleMessage(
            LifecycleKind kind, String subject, boolean dryRun, LifecycleOutcome outcome, boolean partial) {
        if (dryRun) {
            return outcome.overCap()
                    ? "Nothing was changed. A real run is over the bulk cap and would need override=true."
                    : "Nothing was changed. Re-run with dryRun=false"
                            + (kind.destructive() ? " and confirm=\"" + subject + "\"." : ".");
        }
        if (partial) {
            return "Applied unevenly across the cluster. The nodes list says which nodes are in the requested "
                    + "state and which are not; nothing was rolled back.";
        }
        return outcome.anyFailed() ? "Failed on every node it reached." : "Applied.";
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
            @McpToolParam(required = false) Integer type,
            @McpToolParam(required = false) Boolean durable,
            @McpToolParam(required = false) Boolean dryRun) {
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
            case LIST -> alertRules.list(id).stream().map(McpTuningTools::rule).toList();
            case CREATE -> rule(alertRules.create(id, ruleRequest(rule)));
            case UPDATE -> rule(alertRules.update(id, ruleUuid, ruleRequest(rule)));
            case DELETE -> {
                // A delete is irreversible and the model chose the id, so the same
                // confirm gate as message_action applies — by the rule's own name.
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
            @McpToolParam(required = false) String op,
            @McpToolParam(required = false) String key,
            @McpToolParam(required = false) String value) {
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
