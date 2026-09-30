package io.github.sudoitir.artemisstudio.kernel.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * The failed-sign-in throttle (ADR-0143), two keys, both counting unknown usernames too:
 *
 * <ul>
 *   <li>username and source address: 5 failures start an exponential
 *       backoff, cleared by a completed sign-in;
 *   <li>source address across accounts: 30 failures in
 *       10 minutes throttle the address, so one address cannot spray
 *       many accounts. A success does not clear it.
 * </ul>
 *
 * Entries expire and the caches are bounded, so a flood of made-up usernames cannot grow memory.
 *
 * <p>ponytail: per instance. The account lock in the database holds across instances; this is the
 * courtesy layer in front of it, so a shared counter is not worth its cost.
 */
@Component
public class LoginAttemptLimiter {

    private static final int LOCK_AFTER_FAILURES = 5;
    private static final long BASE_LOCKOUT_SECONDS = 5;
    private static final long MAX_LOCKOUT_SECONDS = 300;
    /** Longer than the longest backoff, so an entry never expires while its lock is running. */
    private static final Duration ACCOUNT_FORGET_AFTER = Duration.ofMinutes(15);

    private static final int PER_SOURCE_FAILURES = 30;
    private static final Duration PER_SOURCE_WINDOW = Duration.ofMinutes(10);
    private static final long MAX_ENTRIES = 100_000;

    private final Ticker ticker;

    /** Sliding: every failure rewrites the entry, so it expires after the last failure. */
    private final Cache<Key, Attempts> byAccount;

    /** Fixed window: the entry is only read after it is created, so it expires from the first failure. */
    private final Cache<String, AtomicInteger> bySource;

    public LoginAttemptLimiter() {
        this(Ticker.systemTicker());
    }

    LoginAttemptLimiter(Ticker ticker) {
        this.ticker = ticker;
        this.byAccount = Caffeine.newBuilder()
                .ticker(ticker)
                .maximumSize(MAX_ENTRIES)
                .expireAfterWrite(ACCOUNT_FORGET_AFTER)
                .build();
        this.bySource = Caffeine.newBuilder()
                .ticker(ticker)
                .maximumSize(MAX_ENTRIES)
                .expireAfterWrite(PER_SOURCE_WINDOW)
                .build();
    }

    public boolean isLocked(String username, String sourceIp) {
        AtomicInteger source = bySource.getIfPresent(source(sourceIp));
        if (source != null && source.get() >= PER_SOURCE_FAILURES) {
            return true;
        }
        Attempts a = byAccount.getIfPresent(new Key(username, source(sourceIp)));
        return a != null && a.lockedUntilNanos() != 0 && a.lockedUntilNanos() - ticker.read() > 0;
    }

    public void recordFailure(String username, String sourceIp) {
        bySource.get(source(sourceIp), k -> new AtomicInteger()).incrementAndGet();
        byAccount.asMap().compute(new Key(username, source(sourceIp)), (k, existing) -> {
            int failures = existing == null ? 1 : existing.failures() + 1;
            long lockedUntil = existing == null ? 0 : existing.lockedUntilNanos();
            if (failures >= LOCK_AFTER_FAILURES) {
                long extra = (long) failures - LOCK_AFTER_FAILURES;
                long seconds = Math.min(MAX_LOCKOUT_SECONDS, BASE_LOCKOUT_SECONDS << Math.min(extra, 10));
                lockedUntil = ticker.read() + Duration.ofSeconds(seconds).toNanos();
            }
            // A new value each time: only a real write restarts the entry's expiry.
            return new Attempts(failures, lockedUntil);
        });
    }

    public void recordSuccess(String username, String sourceIp) {
        byAccount.invalidate(new Key(username, source(sourceIp)));
    }

    /** Forget every username-and-source entry of the user, whatever the source; an administrator unlocked them. */
    public void forget(String username) {
        byAccount.asMap().keySet().removeIf(key -> key.username().equals(username));
    }

    private static String source(String sourceIp) {
        return sourceIp == null ? "?" : sourceIp;
    }

    private record Key(String username, String source) {}

    private record Attempts(int failures, long lockedUntilNanos) {}
}
