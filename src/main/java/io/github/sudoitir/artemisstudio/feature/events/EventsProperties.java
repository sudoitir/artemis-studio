package io.github.sudoitir.artemisstudio.feature.events;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Broker-event history (ADR-0028). Everything here except {@code coalesceWindowMillis} is
 * overridable at runtime; the coalescing window is read once by {@link TopicCoalescer} at
 * construction.
 */
@ConfigurationProperties(prefix = "artemis-studio.events")
public record EventsProperties(
        @DefaultValue("72h") Duration retention,
        @DefaultValue("10000") int bufferSize,
        @DefaultValue("1s") Duration flush,
        @DefaultValue("1000") int coalesceWindowMillis,
        @DefaultValue("0 15 * * * *") String reaperCron) {}
