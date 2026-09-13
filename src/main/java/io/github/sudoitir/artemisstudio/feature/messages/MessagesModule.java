package io.github.sudoitir.artemisstudio.feature.messages;

import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureDescriptor;
import io.github.sudoitir.artemisstudio.kernel.plugin.McpToolDef;
import io.github.sudoitir.artemisstudio.kernel.plugin.PermissionDef;
import java.util.List;

/** Message browse, send, move and delete; dead letters. Module descriptor (ADR-0070). */
public final class MessagesModule {

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id("messages")
            .title("Messages")
            .kind(FeatureDescriptor.Kind.FEATURE)
            .permission(new PermissionDef(MessagePermissions.MESSAGE_READ, "Browse messages"))
            .permission(new PermissionDef(MessagePermissions.MESSAGE_SEND, "Send messages"))
            .permission(new PermissionDef(MessagePermissions.MESSAGE_MOVE, "Move or retry messages"))
            .permission(new PermissionDef(MessagePermissions.MESSAGE_DELETE, "Delete or expire messages"))
            .permission(new PermissionDef(MessagePermissions.QUEUE_PURGE, "Purge queues"))
            .apiPrefix("/api/v1/clusters/{clusterId}/queues/{queueName}/messages")
            .apiPrefix("/api/v1/clusters/{clusterId}/dlq")
            .mcpTool(new McpToolDef(
                    "browse_messages",
                    McpToolDef.Posture.READ,
                    "Message headers on a queue, or the full body of one message.",
                    List.of(
                            McpToolDef.Param.note(
                                    "messageId",
                                    "Omit for a capped page of headers. Give the numeric id from a header row "
                                            + "for that message's full body and properties."),
                            McpToolDef.Param.note("filter", "Broker filter expression, e.g. JMSPriority > 5."))))
            .mcpTool(new McpToolDef(
                    "message_action",
                    McpToolDef.Posture.MUTATE,
                    "Move, retry, delete, expire or purge messages on a queue.",
                    List.of(
                            McpToolDef.Param.enumValues("action", MessageAction.values(), "purge is also accepted."),
                            McpToolDef.Param.note(
                                    "messageIds",
                                    "Comma-separated ids from browse_messages, or use filter instead. move "
                                            + "additionally needs targetQueue."),
                            McpToolDef.Param.note("dryRun", McpToolDef.DRY_RUN),
                            McpToolDef.Param.note("confirm", McpToolDef.CONFIRM),
                            McpToolDef.Param.note("override", McpToolDef.OVERRIDE))))
            .mcpTool(new McpToolDef(
                    "send_message",
                    McpToolDef.Posture.MUTATE,
                    "Enqueue one message. Adds, never removes.",
                    List.of(
                            McpToolDef.Param.values("type", List.of("3 (text)", "4 (bytes)"), "Default 3."),
                            McpToolDef.Param.note("durable", "Default true."),
                            McpToolDef.Param.note("dryRun", McpToolDef.DRY_RUN))))
            .build();

    private MessagesModule() {}
}
