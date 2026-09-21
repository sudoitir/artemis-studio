package io.github.sudoitir.artemisstudio.feature.bulk;

import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureDescriptor;
import io.github.sudoitir.artemisstudio.kernel.plugin.TopicDef;

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
            .streamTopic(new TopicDef(BulkRunner.TOPIC, true))
            .build();

    private BulkModule() {}
}
