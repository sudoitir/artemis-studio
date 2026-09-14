package io.github.sudoitir.artemisstudio.feature.messages.mcp;

import io.github.sudoitir.artemisstudio.feature.messages.MessageAction;
import io.github.sudoitir.artemisstudio.feature.messages.MessageService;
import io.github.sudoitir.artemisstudio.feature.messages.MessageService.Outcome;
import io.github.sudoitir.artemisstudio.feature.messages.web.MessageRequests.MessageActionRequest;
import io.github.sudoitir.artemisstudio.feature.messages.web.MessageRequests.SendMessageRequest;
import io.github.sudoitir.artemisstudio.platform.broker.Attempt;
import io.github.sudoitir.artemisstudio.platform.mcp.McpArgs;
import io.github.sudoitir.artemisstudio.platform.mcp.McpErrors;
import io.github.sudoitir.artemisstudio.platform.mcp.McpViews;
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
 * The {@code message_action} and {@code send_message} MCP tools. *
 * <p>Mutating tools (ADR-0045). They go through the same services the REST layer calls, so
 * authorization, the bulk cap and the audit row are the existing ones; this class adds only
 * the model-facing gate: {@code dryRun} defaults to true, a real destructive run needs
 * {@code confirm} to equal the subject's name, and {@code override} is the separate bulk-cap
 * escape that {@code confirm} never satisfies.
 */
@Component
@RequiredArgsConstructor
public class MessagesMcpActionTools {

    private final MessageService messages;

    /** The queue-scoped mutations, as one tool. */
    public enum QueueActionKind {
        MOVE,
        RETRY,
        DELETE,
        EXPIRE,
        PURGE
    }

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
            McpArgs.confirm(q, confirm);
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
                throw new io.github.sudoitir.artemisstudio.platform.broker.BrokerConnectionException(
                        f.kind(), f.detail());
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
                    case Outcome.Partial p ->
                        new McpViews.MutationOutcome(
                                action,
                                subject,
                                false,
                                p.count(),
                                null,
                                false,
                                String.valueOf(p.node()),
                                "Partially applied: " + p.count() + " message(s) were acted on before it stopped ("
                                        + p.error() + "). " + p.notAttempted().size()
                                        + " id(s) were not done, starting with "
                                        + p.notAttempted().getFirst()
                                        + ".");
                };
        };
    }
}
