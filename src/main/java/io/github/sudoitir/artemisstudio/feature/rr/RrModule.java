package io.github.sudoitir.artemisstudio.feature.rr;

import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureDescriptor;

/** Request-reply flows, expectations and latency. Module descriptor (ADR-0070). */
public final class RrModule {

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id("rr")
            .title("Request-reply tracing")
            .kind(FeatureDescriptor.Kind.FEATURE)
            .apiPrefix("/api/v1/clusters/{clusterId}/rr")
            .build();

    private RrModule() {}
}
