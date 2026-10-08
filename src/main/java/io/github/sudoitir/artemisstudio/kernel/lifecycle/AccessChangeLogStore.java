package io.github.sudoitir.artemisstudio.kernel.lifecycle;

import io.github.sudoitir.artemisstudio.kernel.lifecycle.StoreDef.QuotaUnit;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The access change log under the data lifecycle (ADR-0134, ADR-0181). A pending approval is refused when its
 * requester changed anyone's access since it was asked, so the log is kept at least as long as a request can
 * stay open. Like {@link IdempotencyStore} it lives here, not in {@code kernel.security} which owns the
 * table, because this module depends on security.
 */
@Component
class AccessChangeLogStore implements ManagedStore, HousekeepingContributor {

    private static final String TABLE = "access_change_log";
    private static final String OLDER = "at < ?";

    private static final StoreDef DEF = new StoreDef(
            "access-change-log",
            "Access change log",
            List.of(TABLE),
            QuotaUnit.ROWS,
            Duration.ofDays(45),
            Duration.ofDays(45),
            Duration.ofDays(3650));

    private final JdbcTemplate jdbc;

    AccessChangeLogStore(JdbcTemplate jdbc) {
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
