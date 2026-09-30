package io.github.sudoitir.artemisstudio.kernel.lifecycle;

import io.github.sudoitir.artemisstudio.kernel.lifecycle.StoreDef.QuotaUnit;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Expired web sessions under the data lifecycle (ADR-0132). It replaces Spring Session's own cleanup,
 * which every instance ran; here it runs once per installation. The retention is the grace after a
 * session's expiry. {@code spring_session_attributes} rows go with their session through
 * {@code ON DELETE CASCADE}. It lives here, not in {@code kernel.security} which owns the tables,
 * because this module already depends on security.
 */
@Component
class ExpiredSessionStore implements ManagedStore, HousekeepingContributor {

    /** {@code expiry_time} is epoch milliseconds; the cutoff is expiry plus the retention. */
    private static final String EXPIRED = "expiry_time < (extract(epoch from ?::timestamptz) * 1000)";

    private static final StoreDef DEF = new StoreDef(
            "expired-sessions",
            "Expired sessions",
            List.of("spring_session", "spring_session_attributes"),
            QuotaUnit.ROWS,
            Duration.ofMinutes(1),
            Duration.ofMinutes(1),
            Duration.ofDays(1));

    private final JdbcTemplate jdbc;

    ExpiredSessionStore(JdbcTemplate jdbc) {
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
        return LifecycleSql.estimate(jdbc, "spring_session", EXPIRED, cutoff);
    }

    @Override
    public long purgeBatch(Instant cutoff, int limit) {
        return LifecycleSql.deleteBatch(jdbc, "spring_session", EXPIRED, cutoff, limit);
    }
}
