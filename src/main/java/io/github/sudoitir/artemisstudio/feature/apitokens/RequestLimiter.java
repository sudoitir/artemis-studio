package io.github.sudoitir.artemisstudio.feature.apitokens;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fixed one-minute request windows and in-flight counts, keyed by token or user id (ADR-0136).
 * In memory: Studio runs as one instance (ADR-0037).
 */
// ponytail: fixed windows allow up to twice the rate across a minute boundary and live in one JVM;
// move to Bucket4j with a shared store if Studio ever runs clustered.
final class RequestLimiter {

    /** Whether the request may proceed, and what the limit headers say. */
    record Decision(boolean allowed, int limit, int remaining, long resetSeconds) {}

    private record Window(long minute, int count) {}

    private final Map<UUID, Window> windows = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger> inFlight = new ConcurrentHashMap<>();

    /** Counts one request against {@code key}'s current minute unless it is already at {@code limit}. */
    Decision acquire(UUID key, int limit, long nowMillis) {
        long minute = nowMillis / 60_000;
        long reset = Math.max(1, ((minute + 1) * 60_000 - nowMillis + 999) / 1000);
        boolean[] granted = new boolean[1];
        Window w = windows.compute(key, (k, old) -> {
            Window current = old == null || old.minute() != minute ? new Window(minute, 0) : old;
            granted[0] = current.count() < limit;
            return granted[0] ? new Window(minute, current.count() + 1) : current;
        });
        return new Decision(granted[0], limit, Math.max(0, limit - w.count()), reset);
    }

    /** Gives back one request counted in {@code key}'s current minute, when another limit refused it. */
    void refund(UUID key, long nowMillis) {
        long minute = nowMillis / 60_000;
        windows.computeIfPresent(
                key, (k, w) -> w.minute() == minute && w.count() > 0 ? new Window(minute, w.count() - 1) : w);
    }

    /** Takes an in-flight slot for {@code key}; false when {@code limit} are already taken. */
    boolean enter(UUID key, int limit) {
        AtomicInteger n = inFlight.computeIfAbsent(key, k -> new AtomicInteger());
        if (n.incrementAndGet() > limit) {
            n.decrementAndGet();
            return false;
        }
        return true;
    }

    void exit(UUID key) {
        AtomicInteger n = inFlight.get(key);
        if (n != null) {
            n.decrementAndGet();
        }
    }
}
