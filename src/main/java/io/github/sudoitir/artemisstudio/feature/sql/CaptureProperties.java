package io.github.sudoitir.artemisstudio.feature.sql;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Divert-based message capture (ADR-0062).
 *
 * @param brokerRole the broker role Studio's own connection holds. It is what the capture
 *     queue's {@code security-setting} grants consume to, and Studio cannot discover it — the
 *     broker exposes no "who am I" read — so it is stated here and verified by whether the
 *     consumer can actually attach.
 * @param reconcileInterval how often desired and actual capture state are converged. Not a
 *     per-queue poll: one pass reads each live node's divert names once.
 * @param expiry how long a message may sit in a capture queue before the broker drops it.
 *     With {@code auto-create-expiry-resources=false} this bounds an abandoned tap in age,
 *     which — since the divert survives a restart (ADR-0065) — is the half of the bound
 *     that {@code ring-size} cannot provide.
 */
@ConfigurationProperties(prefix = "artemis-studio.capture")
public record CaptureProperties(
        @DefaultValue("amq") String brokerRole,
        @DefaultValue("30s") Duration reconcileInterval,
        @DefaultValue("24h") Duration expiry) {}
