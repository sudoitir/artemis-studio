package io.github.sudoitir.artemisstudio.feature.queues;

import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureDescriptor;
import io.github.sudoitir.artemisstudio.kernel.plugin.McpToolDef;
import io.github.sudoitir.artemisstudio.kernel.plugin.PermissionDef;
import java.util.List;

/** Queue and address lifecycle. Module descriptor (ADR-0070). */
public final class QueuesModule {

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id("queues")
            .title("Queues")
            .kind(FeatureDescriptor.Kind.FEATURE)
            .permission(new PermissionDef(QueuePermissions.QUEUE_CREATE, "Create queues and addresses"))
            .permission(new PermissionDef(QueuePermissions.QUEUE_DELETE, "Destroy queues and addresses"))
            .permission(new PermissionDef(QueuePermissions.QUEUE_UPDATE, "Change a queue's configuration"))
            .permission(new PermissionDef(QueuePermissions.QUEUE_PAUSE, "Pause and resume queues"))
            .apiPrefix("/api/v1/clusters/{clusterId}/queues")
            .apiPrefix("/api/v1/clusters/{clusterId}/addresses")
            .mcpTool(new McpToolDef(
                    "queue_lifecycle",
                    McpToolDef.Posture.MUTATE,
                    "Create, update, pause, resume or destroy a queue, address or divert, cluster-wide.",
                    List.of(
                            McpToolDef.Param.enumValues("kind", LifecycleKind.values(), null),
                            McpToolDef.Param.shape(
                                    "config",
                                    "{ address, routingType (ANYCAST|MULTICAST), durable, filter, maxConsumers, "
                                            + "purgeOnNoConsumers, exclusive, nonDestructive, ringSize, "
                                            + "forwardingAddress, routingName }",
                                    "create_queue needs at least address and routingType. On update_queue only "
                                            + "the fields you send change; address, routingType, name and durable "
                                            + "cannot change on a live queue. create_address reads routingType only. "
                                            + "create_divert needs at least address and forwardingAddress; a divert "
                                            + "created this way persists across broker restarts and will not appear "
                                            + "in the broker's own configuration, and there is no update_divert — "
                                            + "change one by deleting it and creating the replacement."),
                            McpToolDef.Param.note(
                                    "kind.fanout",
                                    "A command targets the cluster and fans out to every live node. The result "
                                            + "is a per-node list: a node already in the requested state reports "
                                            + "ALREADY, and one that was not live reports SKIPPED_NOT_LIVE rather "
                                            + "than a failure. Re-running after a partial application converges."),
                            McpToolDef.Param.note("dryRun", McpToolDef.DRY_RUN),
                            McpToolDef.Param.note("confirm", McpToolDef.CONFIRM))))
            .build();

    private QueuesModule() {}
}
