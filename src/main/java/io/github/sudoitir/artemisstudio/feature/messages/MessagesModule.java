package io.github.sudoitir.artemisstudio.feature.messages;

import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureDescriptor;
import io.github.sudoitir.artemisstudio.kernel.plugin.PermissionDef;

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
            .build();

    private MessagesModule() {}
}
