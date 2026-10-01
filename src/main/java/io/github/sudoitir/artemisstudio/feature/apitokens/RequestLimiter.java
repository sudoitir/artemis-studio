package io.github.sudoitir.artemisstudio.feature.apitokens;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Fixed one-minute request windows, keyed by token or user id, and in-flight counts (ADR-0136).
 * The windows live in the {@code api_request_window} table, so the limit is exact across replicas
 * (ADR-0152): a request is one statement, which counts it against its minute unless the minute is
 * at the limit, and drops the key's older minutes. The in-flight counts stay in memory and are per
 * replica: a token may have its concurrency limit in flight on each replica.
 */
// ponytail: fixed windows allow up to twice the rate across a minute boundary;
// move to Bucket4j with a shared store if that ever matters.
final class RequestLimiter {

    /** Whether the request may proceed, and what the limit headers say. */
    record Decision(boolean allowed, int limit, int remaining, long resetSeconds) {}

    private static final String ACQUIRE = """
            WITH stale AS (DELETE FROM api_request_window WHERE key = ? AND minute < ?)
            INSERT INTO api_request_window AS w (key, minute, count) VALUES (?, ?, 1)
            ON CONFLICT (key, minute) DO UPDATE SET count = w.count + 1 WHERE w.count < ?
            RETURNING w.count""";

    private static final String REFUND =
            "UPDATE api_request_window SET count = count - 1 WHERE key = ? AND minute = ? AND count > 0";

    private final JdbcTemplate jdbc;
    private final Map<UUID, AtomicInteger> inFlight = new ConcurrentHashMap<>();

    RequestLimiter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Counts one request against {@code key}'s current minute unless it is already at {@code limit}. */
    Decision acquire(UUID key, int limit, long nowMillis) {
        long minute = nowMillis / 60_000;
        long reset = Math.max(1, ((minute + 1) * 60_000 - nowMillis + 999) / 1000);
        List<Integer> counted = jdbc.queryForList(ACQUIRE, Integer.class, key, minute, key, minute, limit);
        // No row back: the minute is at its limit, and the statement left it there.
        int count = counted.isEmpty() ? limit : counted.getFirst();
        return new Decision(!counted.isEmpty(), limit, Math.max(0, limit - count), reset);
    }

    /** Gives back one request counted in {@code key}'s current minute, when another limit refused it. */
    void refund(UUID key, long nowMillis) {
        jdbc.update(REFUND, key, nowMillis / 60_000);
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
