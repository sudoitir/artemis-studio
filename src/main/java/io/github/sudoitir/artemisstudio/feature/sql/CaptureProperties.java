package io.github.sudoitir.artemisstudio.feature.sql;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Divert-based message capture (ADR-0062).
 *
 * @param brokerRole the broker role Studio's own connection holds. It is what the capture
 *     queue's {@code security-setting} grants consume to, and Studio cannot discover it — the
 *     broker exposes no "who am I" read — so it is stated here and verified by whether the
 *     consumer can actually attach. It has no default (ADR-0079): capture is refused, naming this
 *     setting, until it is set, because the only role every broker has is one every user holds.
 * @param reconcileInterval how often desired and actual capture state are converged. Not a
 *     per-queue poll: one pass reads each live node's divert names once.
 * @param expiry how long a message may sit in a capture queue before the broker drops it.
 *     With {@code auto-create-expiry-resources=false} this bounds an abandoned tap in age,
 *     which — since the divert survives a restart (ADR-0065) — is the half of the bound
 *     that {@code ring-size} cannot provide.
 * @param maxRingBytes the most one capture queue may hold, in bytes, before the broker drops its
 *     oldest messages. The byte half of the bound; a subscription's ring size is the count half.
 * @param flushInterval how often a drain stores and acknowledges a batch it has not filled. It
 *     bounds how long a captured message takes to become answerable — a live tail's latency — and
 *     how long a copy stays on the capture queue on a low-rate address.
 */
@ConfigurationProperties(prefix = "artemis-studio.capture")
public record CaptureProperties(
        String brokerRole,
        @DefaultValue("30s") Duration reconcileInterval,
        @DefaultValue("24h") Duration expiry,
        @DefaultValue("64MB") DataSize maxRingBytes,
        @DefaultValue("1s") Duration flushInterval) {}
