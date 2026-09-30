package io.github.sudoitir.artemisstudio.kernel.lifecycle;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** The storage samples behind table growth are themselves a store (ADR-0134). */
@Component
class StorageSampleStore implements ManagedStore, HousekeepingContributor {

    private static final String TABLE = "storage_sample";
    private static final String OLDER = "sampled_at < ?";
    private static final StoreDef DEF = new StoreDef(
            "storage-samples",
            "Storage samples",
            List.of(TABLE),
            StoreDef.QuotaUnit.ROWS,
            Duration.ofDays(90),
            Duration.ofDays(7),
            Duration.ofDays(3650));

    private final JdbcTemplate jdbc;

    StorageSampleStore(JdbcTemplate jdbc) {
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
