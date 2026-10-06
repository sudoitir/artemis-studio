package io.github.sudoitir.artemisstudio.feature.bulk;

import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureDescriptor;
import io.github.sudoitir.artemisstudio.kernel.plugin.TopicDef;
import io.github.sudoitir.artemisstudio.kernel.security.Permissions;

/** Bulk queue operations. Module descriptor (ADR-0070, ADR-0093). */
public final class BulkModule {

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id("bulk")
            .title("Bulk operations")
            .kind(FeatureDescriptor.Kind.FEATURE)
            .require("queues")
            .require("messages")
            .require("resources")
            .apiPrefix("/api/v1/clusters/{clusterId}/bulk")
            .streamTopic(TopicDef.data(BulkRunner.TOPIC, Permissions.QUEUE_READ))
            .build();

    private BulkModule() {}
}
