package io.github.sudoitir.artemisstudio.feature.flow;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Client-activity sampling for the flow view (ADR-0081). Every value is runtime-overridable
 * through {@link FlowSettings}; these are the packaged defaults.
 *
 * @param sampleInterval time between sweeps of an observed cluster; never below {@link #MIN_SAMPLE_INTERVAL}
 * @param maxRowsPerNode page size of each producer and consumer listing, so the most one node can return
 * @param demandLease how long a cluster counts as observed after its last observer was seen
 */
@ConfigurationProperties(prefix = "artemis-studio.flow")
public record FlowProperties(
        @DefaultValue("15s") Duration sampleInterval,
        @DefaultValue("5000") int maxRowsPerNode,
        @DefaultValue("60s") Duration demandLease) {

    /** A floor under the sampling interval: below it, sampling is load, not observation. */
    public static final Duration MIN_SAMPLE_INTERVAL = Duration.ofSeconds(10);
}
