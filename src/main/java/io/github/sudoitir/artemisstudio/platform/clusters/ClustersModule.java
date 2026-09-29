package io.github.sudoitir.artemisstudio.platform.clusters;

import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureDescriptor;
import io.github.sudoitir.artemisstudio.kernel.plugin.PermissionDef;
import io.github.sudoitir.artemisstudio.kernel.plugin.TopicDef;

/** Cluster registration, topology, HA state and environments. Module descriptor (ADR-0070). */
public final class ClustersModule {

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id("clusters")
            .title("Clusters")
            .kind(FeatureDescriptor.Kind.PLATFORM)
            .required(true)
            .permission(new PermissionDef(ClusterPermissions.CLUSTER_WRITE, "Register or remove clusters"))
            .permission(new PermissionDef(ClusterPermissions.ENVIRONMENT_READ, "View environments", true))
            .permission(new PermissionDef(
                    ClusterPermissions.ENVIRONMENT_WRITE, "Create, rename, or remove environments", true))
            .streamTopic(TopicDef.signal("topology"))
            .streamTopic(TopicDef.signal("health"))
            .build();

    private ClustersModule() {}
}
