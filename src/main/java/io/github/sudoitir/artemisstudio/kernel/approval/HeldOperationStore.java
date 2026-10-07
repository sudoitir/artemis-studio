package io.github.sudoitir.artemisstudio.kernel.approval;

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
 * Held operations under the data lifecycle (ADR-0134): a request that ended goes once it ended longer ago than the
 * retention, with its timeline; an open one is never purged. The table's own trigger refuses to delete anything open
 * or ended within the last day, the shortest retention allowed here.
 */
@Component
class HeldOperationStore implements ManagedStore, HousekeepingContributor {

    private static final String TABLE = "held_operation";
    private static final String ENDED = "state NOT IN (" + HeldStore.OPEN + ") AND finished_at < ?";

    private static final StoreDef DEF = new StoreDef(
            "held-operations",
            "Held operations",
            List.of(TABLE, "held_operation_event"),
            StoreDef.QuotaUnit.ROWS,
            Duration.ofDays(90),
            Duration.ofDays(1),
            Duration.ofDays(3650));

    private final JdbcTemplate jdbc;

    HeldOperationStore(JdbcTemplate jdbc) {
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
        return LifecycleSql.estimate(jdbc, TABLE, ENDED, cutoff);
    }

    @Override
    public long purgeBatch(Instant cutoff, int limit) {
        return LifecycleSql.deleteBatch(jdbc, TABLE, ENDED, cutoff, limit);
    }
}
