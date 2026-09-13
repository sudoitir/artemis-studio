package io.github.sudoitir.artemisstudio.feature.rr;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Request-reply tracing. {@code defaultDeadlineMs} applies only when neither the message
 * nor its expectation carries one. Everything here except {@code percentileWindow} is
 * runtime-overridable (ADR-0047); the percentile window is a Micrometer
 * {@code distributionStatisticExpiry} fixed when the {@code Timer} is registered, so
 * changing it later would silently not apply.
 */
@ConfigurationProperties(prefix = "artemis-studio.rr")
public record RrProperties(
        @DefaultValue("30000") int defaultDeadlineMs,
        @DefaultValue("5s") Duration sweepInterval,
        @DefaultValue("5s") Duration sampleInterval,
        @DefaultValue("15m") Duration percentileWindow,
        @DefaultValue("4096") int payloadCaptureBytes,
        @DefaultValue("7d") Duration retention,
        @DefaultValue("0 20 3 * * *") String reaperCron) {}
