package io.github.sudoitir.artemisstudio.feature.bulk;

import io.github.sudoitir.artemisstudio.kernel.lifecycle.HousekeepingContributor;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.LifecycleSql;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.ManagedStore;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.PurgeEstimate;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.StoreDef;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.StoreUsage;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Bulk runs under the data lifecycle (ADR-0132): a finished run goes once it is older than the
 * retention, and a preview nobody executed goes when it expires, whatever the retention. A run's
 * items go with it through {@code ON DELETE CASCADE}. A previewed or running run is never touched
 * before its own expiry.
 */
@Component
class BulkRunStore implements ManagedStore, HousekeepingContributor {

    private static final String RUNS = "bulk_run";
    private static final String PREDICATE = "(status IN ('SUCCEEDED', 'PARTIAL', 'FAILED', 'STOPPED', 'INTERRUPTED')"
            + " AND coalesce(finished_at, created_at) < ?) OR (status = 'PREVIEWED' AND expires_at < now())";

    private final JdbcTemplate jdbc;

    BulkRunStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ManagedStore> stores() {
        return List.of(this);
    }

    @Override
    public StoreDef def() {
        return new StoreDef(
                "bulk-runs",
                "Bulk runs",
                List.of(RUNS, "bulk_run_item"),
                StoreDef.QuotaUnit.ROWS,
                Duration.ofDays(90),
                Duration.ofDays(1),
                Duration.ofDays(3650));
    }

    @Override
    public StoreUsage usage() {
        return LifecycleSql.usage(jdbc, def().tables());
    }

    @Override
    public PurgeEstimate preview(Instant cutoff) {
        return LifecycleSql.estimate(jdbc, RUNS, PREDICATE, cutoff);
    }

    @Override
    public long purgeBatch(Instant cutoff, int limit) {
        return LifecycleSql.deleteBatch(jdbc, RUNS, PREDICATE, cutoff, limit);
    }
}
