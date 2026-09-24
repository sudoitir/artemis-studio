package io.github.sudoitir.artemisstudio.feature.plugins.messaging;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Plugin messaging (ADR-0111).
 *
 * @param reconcileInterval how often registrations are converged onto the broker; also how soon a
 *     revoked permission, a failover or a stopped plugin is acted on
 * @param tapRingSize how many copies a tap queue holds before dropping the oldest; its byte bound,
 *     expiry and broker role are capture's ({@code artemis-studio.capture.*})
 */
@ConfigurationProperties(prefix = "artemis-studio.plugins.messaging")
public record PluginMessagingProperties(
        @DefaultValue("10s") Duration reconcileInterval,
        @DefaultValue("10000") long tapRingSize) {}
