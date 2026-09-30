package io.github.sudoitir.artemisstudio.kernel.replica;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The timings of a replicated installation (ADR-0148).
 *
 * @param heartbeat how often a replica records that it is alive, using database time
 * @param ttl how old a heartbeat may be before its replica counts as gone
 * @param drainDelay how long a stopping replica keeps serving after it reports itself not ready, so a
 *     load balancer notices before the streams close; longer than the balancer's detection time
 * @param runGrace how long a stopping replica waits for the bulk runs and transfers it is executing to
 *     finish before it asks them to stop and records them as interrupted
 */
@ConfigurationProperties(prefix = "artemis-studio.ha")
public record HaProperties(
        @DefaultValue("5s") Duration heartbeat,
        @DefaultValue("15s") Duration ttl,
        @DefaultValue("5s") Duration drainDelay,
        @DefaultValue("20s") Duration runGrace) {}
