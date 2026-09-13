package io.github.sudoitir.artemisstudio.feature.metrics;

import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureDescriptor;

/** Historical metric queries. Module descriptor (ADR-0070). */
public final class MetricsModule {

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id("metrics")
            .title("Metrics")
            .kind(FeatureDescriptor.Kind.FEATURE)
            .apiPrefix("/api/v1/clusters/{clusterId}/metrics")
            .build();

    private MetricsModule() {}
}
