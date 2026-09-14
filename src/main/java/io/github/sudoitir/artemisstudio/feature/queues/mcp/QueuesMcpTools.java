package io.github.sudoitir.artemisstudio.feature.queues.mcp;

import io.github.sudoitir.artemisstudio.feature.queues.LifecycleKind;
import io.github.sudoitir.artemisstudio.feature.queues.LifecycleRequests.CreateAddressRequest;
import io.github.sudoitir.artemisstudio.feature.queues.LifecycleRequests.CreateDivertRequest;
import io.github.sudoitir.artemisstudio.feature.queues.LifecycleRequests.CreateQueueRequest;
import io.github.sudoitir.artemisstudio.feature.queues.LifecycleRequests.UpdateQueueRequest;
import io.github.sudoitir.artemisstudio.feature.queues.QueueLifecycleService;
import io.github.sudoitir.artemisstudio.platform.broker.Attempt;
import io.github.sudoitir.artemisstudio.platform.clusters.LifecycleOutcome;
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
 * The {@code queue_lifecycle} MCP tool. *
 * <p>Mutating tools (ADR-0045). They go through the same services the REST layer calls, so
 * authorization, the bulk cap and the audit row are the existing ones; this class adds only
 * the model-facing gate: {@code dryRun} defaults to true, a real destructive run needs
 * {@code confirm} to equal the subject's name, and {@code override} is the separate bulk-cap
 * escape that {@code confirm} never satisfies.
 */
@Component
@RequiredArgsConstructor
public class QueuesMcpTools {

    private final QueueLifecycleService lifecycle;

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
            Long ringSize,
            String forwardingAddress,
            String routingName,
            Boolean acknowledgeCaptureShadowing) {}

    /**
     * Queue, address and divert lifecycle, as <b>one</b> tool discriminated by
     * {@code kind} rather than ten — the tool-count budget the MCP capability sets is
     * a real constraint, and ten near-identical verbs would spend it for nothing.
     *
     * <p>Delegates to {@link io.github.sudoitir.artemisstudio.feature.queues.QueueLifecycleService},
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
            McpArgs.confirm(subject, confirm);
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
                    case CREATE_DIVERT -> lifecycle.createDivert(id, divertRequest(subject, body), dry);
                    case DELETE_DIVERT -> lifecycle.deleteDivert(id, subject, dry);
                }));
    }

    private static QueueConfigBody parseConfig(String raw) {
        if (raw == null || raw.isBlank()) {
            return new QueueConfigBody(null, null, null, null, null, null, null, null, null, null, null, null);
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

    /**
     * A divert from the shared config body. The result of creating one says that it
     * persists on the broker and is absent from that broker's configuration
     * (ADR-0065) — an agent acting on Studio's behalf must not report it as
     * temporary any more than the UI may.
     */
    private static CreateDivertRequest divertRequest(String name, QueueConfigBody body) {
        if (body.address() == null || body.forwardingAddress() == null) {
            throw McpErrors.invalidParams("create_divert needs config with at least address and forwardingAddress.");
        }
        return new CreateDivertRequest(
                name,
                body.routingName(),
                body.address(),
                body.forwardingAddress(),
                body.exclusive(),
                body.filter(),
                body.routingType(),
                body.acknowledgeCaptureShadowing());
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
                        throw new io.github.sudoitir.artemisstudio.platform.broker.BrokerConnectionException(
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
}
