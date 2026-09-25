package io.github.sudoitir.artemisstudio.feature.plugins.messaging;

import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Plugin messaging (ADR-0111, ADR-0112).
 *
 * @param reconcileInterval how often registrations are converged onto the broker; also how soon a
 *     revoked permission, a failover or a stopped plugin is acted on
 * @param tapRingSize how many copies a tap queue holds before dropping the oldest; its byte bound,
 *     expiry and broker role are capture's ({@code artemis-studio.capture.*})
 * @param maxThreads the most threads plugin message handlers run on, per node and per kind (taps,
 *     consumers). Plugin drains have this pool to themselves, so a plugin that blocks waits here and
 *     never on the threads capture and operator sessions use. At least 2, the Core client's minimum.
 */
@Validated
@ConfigurationProperties(prefix = "artemis-studio.plugins.messaging")
public record PluginMessagingProperties(
        @DefaultValue("10s") Duration reconcileInterval,
        @DefaultValue("10000") long tapRingSize,
        @DefaultValue("64") @Min(2) int maxThreads) {}
