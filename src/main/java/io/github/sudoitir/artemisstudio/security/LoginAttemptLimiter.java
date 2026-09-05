package io.github.sudoitir.artemisstudio.security;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Per (username, source IP) failed-login throttle, modelled on
 * {@link io.github.sudoitir.artemisstudio.scheduler.NodeCallLimiter}'s per-key
 * bucket shape. Exponential lockout, cleared on success.
 *
 * <p>ponytail: in-memory only, single instance. Correct until v1.0's
 * multi-instance HA; revisit then (a shared Postgres or cache-backed counter).
 */
@Component
public class LoginAttemptLimiter {

    private static final int LOCK_AFTER_FAILURES = 5;
    private static final long BASE_LOCKOUT_SECONDS = 5;
    private static final long MAX_LOCKOUT_SECONDS = 300;

    private final Map<String, Attempts> byKey = new ConcurrentHashMap<>();

    public boolean isLocked(String username, String sourceIp) {
        Attempts a = byKey.get(key(username, sourceIp));
        return a != null && a.lockedUntil != null && a.lockedUntil.isAfter(Instant.now());
    }

    public void recordFailure(String username, String sourceIp) {
        byKey.compute(key(username, sourceIp), (k, existing) -> {
            Attempts a = existing == null ? new Attempts() : existing;
            a.failures++;
            if (a.failures >= LOCK_AFTER_FAILURES) {
                long extra = a.failures - LOCK_AFTER_FAILURES;
                long seconds = Math.min(MAX_LOCKOUT_SECONDS, BASE_LOCKOUT_SECONDS << Math.min(extra, 10));
                a.lockedUntil = Instant.now().plusSeconds(seconds);
            }
            return a;
        });
    }

    public void recordSuccess(String username, String sourceIp) {
        byKey.remove(key(username, sourceIp));
    }

    private static String key(String username, String sourceIp) {
        return username + "|" + (sourceIp == null ? "?" : sourceIp);
    }

    private static final class Attempts {
        int failures;
        Instant lockedUntil;
    }
}
