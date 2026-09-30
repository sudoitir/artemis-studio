package io.github.sudoitir.artemisstudio.kernel.lifecycle;

import io.github.sudoitir.artemisstudio.kernel.lifecycle.StoreDef.QuotaUnit;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Idempotency keys under the data lifecycle (ADR-0134, ADR-0148). The retention is how long a repeat of a
 * key replays the first result, 24 hours by default and never shorter. Like {@link ExpiredSessionStore} it
 * lives here, not in {@code kernel.security} which owns the table, because this module depends on security.
 */
@Component
class IdempotencyStore implements ManagedStore, HousekeepingContributor {

    private static final String TABLE = "idempotency_record";
    private static final String OLDER = "created_at < ?";

    private static final StoreDef DEF = new StoreDef(
            "idempotency-keys",
            "Idempotency keys",
            List.of(TABLE),
            QuotaUnit.ROWS,
            Duration.ofHours(24),
            Duration.ofHours(24),
            Duration.ofDays(30));

    private final JdbcTemplate jdbc;

    IdempotencyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ManagedStore> stores() {
        return List.of(this);
    }

    @Override
    public StoreDef def() {
        return DEF;
    }

    @Override
    public StoreUsage usage() {
        return LifecycleSql.usage(jdbc, DEF.tables());
    }

    @Override
    public PurgeEstimate preview(Instant cutoff) {
        return LifecycleSql.estimate(jdbc, TABLE, OLDER, cutoff);
    }

    @Override
    public long purgeBatch(Instant cutoff, int limit) {
        return LifecycleSql.deleteBatch(jdbc, TABLE, OLDER, cutoff, limit);
    }
}
