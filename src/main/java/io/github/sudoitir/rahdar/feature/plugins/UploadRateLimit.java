package io.github.sudoitir.rahdar.feature.plugins;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * At most {@link #LIMIT} uploads per user per hour (design.md §7): each one is parsed and hashed,
 * and a jar can be 50 MB. The hour starts at the user's first upload and the count is in the
 * {@code upload_window} table, so the limit holds whichever replica takes the upload (ADR-0196).
 */
@Component
class UploadRateLimit {

    static final int LIMIT = 5;
    static final Duration WINDOW = Duration.ofHours(1);

    /** Counts one upload unless the window is full; an expired window starts again from one. */
    private static final String ACQUIRE = """
            INSERT INTO upload_window AS w (user_id, count, window_ends_at) VALUES (?, 1, ?)
            ON CONFLICT (user_id) DO UPDATE SET
                count = CASE WHEN w.window_ends_at <= ? THEN 1 ELSE w.count + 1 END,
                window_ends_at = CASE WHEN w.window_ends_at <= ? THEN EXCLUDED.window_ends_at ELSE w.window_ends_at END
            WHERE w.window_ends_at <= ? OR w.count < ?
            RETURNING w.count""";

    private final JdbcTemplate jdbc;
    private final Clock clock;
    /** Commits on its own: an upload that fails and rolls back still used one of the user's uploads. */
    private final TransactionTemplate ownTransaction;

    UploadRateLimit(JdbcTemplate jdbc, Clock clock, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.ownTransaction = new TransactionTemplate(transactions);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Records an upload by {@code userId}, or answers false without recording when the window is full. */
    boolean tryAcquire(UUID userId) {
        Instant now = clock.instant();
        Timestamp at = Timestamp.from(now);
        List<Integer> counted = ownTransaction.execute(_ ->
                jdbc.queryForList(ACQUIRE, Integer.class, userId, Timestamp.from(now.plus(WINDOW)), at, at, at, LIMIT));
        return counted != null && !counted.isEmpty();
    }
}
