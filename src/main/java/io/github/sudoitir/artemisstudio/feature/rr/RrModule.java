package io.github.sudoitir.artemisstudio.feature.rr;

import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureDescriptor;

/** Request-reply flows, expectations and latency. Module descriptor (ADR-0070). */
public final class RrModule {

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id("rr")
            .title("Request-reply tracing")
            .kind(FeatureDescriptor.Kind.FEATURE)
            .apiPrefix("/api/v1/clusters/{clusterId}/rr")
            .settingKey(RrSettings.RETENTION_DAYS)
            .settingKey(RrSettings.REAPER_CRON)
            .settingKey(RrSettings.DEFAULT_DEADLINE_MS)
            .settingKey(RrSettings.PAYLOAD_CAPTURE_BYTES)
            .settingKey(RrSettings.SWEEP_INTERVAL)
            .settingKey(RrSettings.SAMPLE_INTERVAL)
            .build();

    private RrModule() {}
}
