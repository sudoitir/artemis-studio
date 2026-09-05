package io.github.sudoitir.artemisstudio.scheduler;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential backoff with jitter for notification delivery retries — the same
 * shape as {@code broker.core.Backoff} (ADR-0026), reimplemented here stateless
 * because a delivery's attempt count already lives on
 * {@code alert_delivery.attempts}, not in an in-memory map keyed by node.
 */
final class AlertBackoff {

    private AlertBackoff() {}

    static Duration delayFor(int failures, Duration initial, Duration max) {
        long baseMillis =
                Math.min(max.toMillis(), initial.toMillis() * (1L << Math.min(Math.max(failures - 1, 0), 20)));
        long jittered = baseMillis / 2 + ThreadLocalRandom.current().nextLong(baseMillis / 2 + 1);
        return Duration.ofMillis(jittered);
    }
}
