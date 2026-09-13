package io.github.sudoitir.artemisstudio.feature.resources;

import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureDescriptor;
import io.github.sudoitir.artemisstudio.kernel.plugin.PermissionDef;
import io.github.sudoitir.artemisstudio.kernel.plugin.TopicDef;

/** Cross-node live views and connection control. Module descriptor (ADR-0070). */
public final class ResourcesModule {

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id("resources")
            .title("Resources")
            .kind(FeatureDescriptor.Kind.FEATURE)
            .permission(new PermissionDef(
                    ResourcePermissions.CONNECTION_CLOSE,
                    "Close client connections, sessions and an address's consumers"))
            .apiPrefix("/api/v1/clusters/{clusterId}/consumers")
            .apiPrefix("/api/v1/clusters/{clusterId}/sessions")
            .apiPrefix("/api/v1/clusters/{clusterId}/connections")
            .apiPrefix("/api/v1/clusters/{clusterId}/producers")
            .apiPrefix("/api/v1/clusters/{clusterId}/nodes/{nodeId}/connections")
            .apiPrefix("/api/v1/clusters/{clusterId}/nodes/{nodeId}/sessions")
            .apiPrefix("/api/v1/clusters/{clusterId}/nodes/{nodeId}/consumers")
            .apiPrefix("/api/v1/clusters/{clusterId}/addresses/{address}/consumers")
            .streamTopic(TopicDef.signal("consumers"))
            .streamTopic(TopicDef.signal("sessions"))
            .streamTopic(TopicDef.signal("connections"))
            .build();

    private ResourcesModule() {}
}
