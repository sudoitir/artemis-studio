package io.github.sudoitir.artemisstudio.kernel.security;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The account lock (ADR-0143): 10 consecutive failed sign-ins lock the
 * account for 15 minutes, in {@code app_user} so it holds across instances. Every sign-in path
 * reports its outcome here and nowhere else:
 *
 * <ul>
 *   <li>{@link #failed}: a wrong password, or a wrong second factor;
 *   <li>{@link #completed}: the whole sign-in finished, second factor included. A correct password
 *       alone is not a success.
 * </ul>
 *
 * Both also feed the in-memory {@link LoginAttemptLimiter}. The database writes run in their own
 * transaction with atomic SQL, so the rollback of the failed login that caused them cannot undo
 * them, and concurrent failures cannot lose a count.
 */
@Component
public class AccountLockout {

    static final int LOCK_AFTER_FAILURES = 10;
    static final Duration LOCK_FOR = Duration.ofMinutes(15);

    /**
     * A lock still running is left alone (a failure while locked neither extends it nor counts); a
     * lock that has passed starts the count again at this failure. Returns whether this statement
     * set the lock: {@code now()} is the transaction's start, so only this transaction can have
     * written a lock that equals it.
     */
    private static final String RECORD_FAILURE = """
            UPDATE app_user SET
                failed_login_count = CASE
                    WHEN locked_until > now() THEN failed_login_count
                    WHEN locked_until <= now() THEN 1
                    ELSE failed_login_count + 1 END,
                locked_until = CASE
                    WHEN locked_until > now() THEN locked_until
                    WHEN locked_until IS NULL AND failed_login_count + 1 >= :max
                        THEN now() + :seconds * interval '1 second'
                    END
            WHERE id = :id
            RETURNING COALESCE(locked_until = now() + :seconds * interval '1 second', false)
            """;

    private static final String RESET = """
            UPDATE app_user SET failed_login_count = 0, locked_until = NULL
            WHERE id = :id AND (failed_login_count <> 0 OR locked_until IS NOT NULL)
            """;

    private final JdbcClient jdbc;
    private final LoginAttemptLimiter limiter;
    private final AuthenticationAudit audit;
    private final TransactionTemplate ownTransaction;

    public AccountLockout(
            JdbcClient jdbc,
            LoginAttemptLimiter limiter,
            AuthenticationAudit audit,
            PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.limiter = limiter;
        this.audit = audit;
        this.ownTransaction = new TransactionTemplate(transactions);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Whether the account is locked right now, by the database's clock. */
    public boolean isLocked(UUID userId) {
        return jdbc.sql("SELECT COALESCE(locked_until > now(), false) FROM app_user WHERE id = :id")
                .param("id", userId)
                .query(Boolean.class)
                .optional()
                .orElse(false);
    }

    /**
     * A wrong password or second factor. {@code userId} is null when the username names no account
     * that can lock (unknown, or not local), which still counts against the source address.
     */
    public void failed(UUID userId, String username, HttpServletRequest request) {
        limiter.recordFailure(username, request.getRemoteAddr());
        if (userId == null) {
            return;
        }
        boolean locked = Boolean.TRUE.equals(ownTransaction.execute(status -> jdbc.sql(RECORD_FAILURE)
                .param("max", LOCK_AFTER_FAILURES)
                .param("seconds", (int) LOCK_FOR.toSeconds())
                .param("id", userId)
                .query(Boolean.class)
                .optional()
                .orElse(false)));
        if (locked) {
            audit.accountLocked(username, request);
        }
    }

    /** The whole sign-in finished: clear the count and the limiter's entry for this username and source. */
    public void completed(UUID userId, String username, HttpServletRequest request) {
        limiter.recordSuccess(username, request.getRemoteAddr());
        reset(userId);
    }

    /** An administrator lifts the lock and the count, and the limiter's entries for the username. */
    public void unlock(UUID userId, String username) {
        reset(userId);
        limiter.forget(username);
    }

    private void reset(UUID userId) {
        ownTransaction.executeWithoutResult(
                status -> jdbc.sql(RESET).param("id", userId).update());
    }
}
