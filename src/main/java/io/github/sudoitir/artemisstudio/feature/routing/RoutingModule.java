package io.github.sudoitir.artemisstudio.feature.routing;

import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureDescriptor;

/** Diverts and bridges. Module descriptor (ADR-0070). */
public final class RoutingModule {

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id("routing")
            .title("Routing")
            .kind(FeatureDescriptor.Kind.FEATURE)
            .require("queues")
            .apiPrefix("/api/v1/clusters/{clusterId}/diverts")
            .apiPrefix("/api/v1/clusters/{clusterId}/bridges")
            .build();

    private RoutingModule() {}
}
