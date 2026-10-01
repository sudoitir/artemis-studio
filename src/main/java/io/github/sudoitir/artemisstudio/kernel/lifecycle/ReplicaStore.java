package io.github.sudoitir.artemisstudio.kernel.lifecycle;

import io.github.sudoitir.artemisstudio.kernel.lifecycle.StoreDef.QuotaUnit;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Replicas that stopped, or crashed, under the data lifecycle (ADR-0134). The retention counts from
 * the stop, or from the last heartbeat of a replica that never recorded one. It is never shorter
 * than an hour, because the crash-loop guard counts crashes over fifteen minutes. The table
 * belongs to {@code kernel.replica}; this module only purges it, as it does for sessions.
 */
@Component
class ReplicaStore implements ManagedStore, HousekeepingContributor {

    private static final String TABLE = "studio_replica";

    private static final String GONE = "coalesce(stopped_at, heartbeat_at) < ?";

    private static final StoreDef DEF = new StoreDef(
            "replicas",
            "Replicas",
            List.of(TABLE),
            QuotaUnit.ROWS,
            Duration.ofDays(1),
            Duration.ofHours(1),
            Duration.ofDays(30));

    private final JdbcTemplate jdbc;

    ReplicaStore(JdbcTemplate jdbc) {
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
        return LifecycleSql.estimate(jdbc, TABLE, GONE, cutoff);
    }

    @Override
    public long purgeBatch(Instant cutoff, int limit) {
        return LifecycleSql.deleteBatch(jdbc, TABLE, GONE, cutoff, limit);
    }
}
