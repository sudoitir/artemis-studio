package io.github.sudoitir.rahdar.kernel.security;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The failed-sign-in throttle (ADR-0144), two keys, both counting unknown usernames too:
 *
 * <ul>
 *   <li>username and source address: 5 failures start an exponential
 *       backoff, cleared by a completed sign-in;
 *   <li>source address across accounts: 30 failures in
 *       10 minutes throttle the address, so one address cannot spray
 *       many accounts. A success does not clear it.
 * </ul>
 *
 * The counts live in the {@code login_attempt} table, so failures that reach different replicas add up
 * (ADR-0196). Rows expire, and housekeeping removes the ones nobody came back to, so a flood of made-up
 * usernames cannot grow the table for long.
 */
@Component
public class LoginAttemptLimiter {

    private static final int LOCK_AFTER_FAILURES = 5;
    private static final long BASE_LOCKOUT_SECONDS = 5;
    private static final long MAX_LOCKOUT_SECONDS = 300;
    /** Longer than the longest backoff, so a row never expires while its lock is running. */
    private static final Duration ACCOUNT_FORGET_AFTER = Duration.ofMinutes(15);

    private static final int PER_SOURCE_FAILURES = 30;
    private static final Duration PER_SOURCE_WINDOW = Duration.ofMinutes(10);

    private static final String IS_LOCKED = """
            SELECT EXISTS (
                SELECT 1 FROM login_attempt
                WHERE expires_at > ?
                  AND ((key = ? AND failures >= ?) OR (key = ? AND locked_until > ?)))""";

    /** Sliding: every failure moves the expiry, so a row expires after the last failure. */
    private static final String FAIL_ACCOUNT = """
            INSERT INTO login_attempt AS a (key, failures, locked_until, expires_at) VALUES (?, 1, NULL, ?)
            ON CONFLICT (key) DO UPDATE SET
                failures = CASE WHEN a.expires_at <= ? THEN 1 ELSE a.failures + 1 END,
                locked_until = CASE
                    WHEN a.expires_at > ? AND a.failures + 1 >= ?
                        THEN ?::timestamptz + make_interval(secs => LEAST(?, ? * power(2, LEAST(a.failures + 1 - ?, 10))))
                    WHEN a.expires_at > ? THEN a.locked_until
                END,
                expires_at = EXCLUDED.expires_at""";

    /** Fixed window: the expiry is set by the first failure and kept, so the window starts there. */
    private static final String FAIL_SOURCE = """
            INSERT INTO login_attempt AS a (key, failures, locked_until, expires_at) VALUES (?, 1, NULL, ?)
            ON CONFLICT (key) DO UPDATE SET
                failures = CASE WHEN a.expires_at <= ? THEN 1 ELSE a.failures + 1 END,
                expires_at = CASE WHEN a.expires_at <= ? THEN EXCLUDED.expires_at ELSE a.expires_at END""";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    /** Writes commit on their own: a failed sign-in rolls its request back, and the failure must stay counted. */
    private final TransactionTemplate ownTransaction;

    public LoginAttemptLimiter(JdbcTemplate jdbc, Clock clock, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.ownTransaction = new TransactionTemplate(transactions);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public boolean isLocked(String username, String sourceIp) {
        Timestamp now = Timestamp.from(clock.instant());
        return Boolean.TRUE.equals(jdbc.queryForObject(
                IS_LOCKED,
                Boolean.class,
                now,
                sourceKey(sourceIp),
                PER_SOURCE_FAILURES,
                accountKey(username, sourceIp),
                now));
    }

    public void recordFailure(String username, String sourceIp) {
        Instant now = clock.instant();
        Timestamp at = Timestamp.from(now);
        ownTransaction.executeWithoutResult(_ -> {
            jdbc.update(FAIL_SOURCE, sourceKey(sourceIp), Timestamp.from(now.plus(PER_SOURCE_WINDOW)), at, at);
            jdbc.update(
                    FAIL_ACCOUNT,
                    accountKey(username, sourceIp),
                    Timestamp.from(now.plus(ACCOUNT_FORGET_AFTER)),
                    at,
                    at,
                    LOCK_AFTER_FAILURES,
                    at,
                    MAX_LOCKOUT_SECONDS,
                    BASE_LOCKOUT_SECONDS,
                    LOCK_AFTER_FAILURES,
                    at);
        });
    }

    public void recordSuccess(String username, String sourceIp) {
        ownTransaction.executeWithoutResult(
                _ -> jdbc.update("DELETE FROM login_attempt WHERE key = ?", accountKey(username, sourceIp)));
    }

    /** Forget every username-and-source entry of the user, whatever the source; an administrator unlocked them. */
    public void forget(String username) {
        ownTransaction.executeWithoutResult(
                _ -> jdbc.update("DELETE FROM login_attempt WHERE starts_with(key, ?)", accountPrefix(username)));
    }

    /** The username's length leads, so no username can run into the source that follows it. */
    private static String accountPrefix(String username) {
        String name = username == null ? "?" : username;
        return "a:" + name.length() + ":" + name + ":";
    }

    private static String accountKey(String username, String sourceIp) {
        return accountPrefix(username) + source(sourceIp);
    }

    private static String sourceKey(String sourceIp) {
        return "s:" + source(sourceIp);
    }

    private static String source(String sourceIp) {
        return sourceIp == null ? "?" : sourceIp;
    }
}
