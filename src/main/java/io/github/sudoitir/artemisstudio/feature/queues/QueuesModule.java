package io.github.sudoitir.artemisstudio.feature.queues;

import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureDescriptor;
import io.github.sudoitir.artemisstudio.kernel.plugin.PermissionDef;

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
            .build();

    private QueuesModule() {}
}
