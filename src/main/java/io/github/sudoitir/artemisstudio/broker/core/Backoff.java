package io.github.sudoitir.artemisstudio.broker.core;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential backoff with jitter for Core connect attempts (ADR-0026, D4). Not
 * thread-safe; {@link CoreSubscriptionManager} calls it from one reconcile at a
 * time.
 */
final class Backoff {

    private final Duration initial;
    private final Duration max;
    private int failures;
    private Instant nextAttempt = Instant.EPOCH;

    Backoff(Duration initial, Duration max) {
        this.initial = initial;
        this.max = max;
    }

    boolean notDueYet() {
        return Instant.now().isBefore(nextAttempt);
    }

    void recordFailure() {
        failures++;
        long baseMillis = Math.min(max.toMillis(), initial.toMillis() * (1L << Math.min(failures - 1, 20)));
        long jittered = baseMillis / 2 + ThreadLocalRandom.current().nextLong(baseMillis / 2 + 1);
        nextAttempt = Instant.now().plusMillis(jittered);
    }
}
