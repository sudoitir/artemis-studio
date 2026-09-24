package io.github.sudoitir.artemisstudio.feature.alerting;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Notification delivery (ADR-0036, ADR-0105). A separate {@code RestClient} from the broker one — no
 * sharing of the per-node rate limiter or broker TLS bundles with an outbound webhook call.
 *
 * @param publicUrl the address operators reach Studio at, e.g. {@code https://studio.example.com};
 *     when set, a notification links back to the cluster's alerts. Blank means no link.
 * @param emailTimeout connect, read and write timeout of one SMTP delivery
 */
@ConfigurationProperties(prefix = "artemis-studio.alerting")
public record AlertingProperties(
        @DefaultValue("5s") Duration dispatchInterval,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("10s") Duration connectTimeout,
        @DefaultValue("5s") Duration initialBackoff,
        @DefaultValue("10m") Duration maxBackoff,
        @DefaultValue("") String publicUrl,
        @DefaultValue("15s") Duration emailTimeout) {}
