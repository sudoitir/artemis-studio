package io.github.sudoitir.rahdar.platform.scrape;

import io.github.sudoitir.rahdar.kernel.lifecycle.HousekeepingContributor;
import io.github.sudoitir.rahdar.kernel.lifecycle.LifecycleSql;
import io.github.sudoitir.rahdar.kernel.lifecycle.ManagedStore;
import io.github.sudoitir.rahdar.kernel.lifecycle.PurgeEstimate;
import io.github.sudoitir.rahdar.kernel.lifecycle.StoreDef;
import io.github.sudoitir.rahdar.kernel.lifecycle.StoreDef.QuotaUnit;
import io.github.sudoitir.rahdar.kernel.lifecycle.StoreUsage;
import io.github.sudoitir.rahdar.kernel.lifecycle.partition.Grain;
import io.github.sudoitir.rahdar.kernel.lifecycle.partition.PartitionDrops;
import io.github.sudoitir.rahdar.kernel.lifecycle.partition.PartitionSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The {@code metrics} store (ADR-0134): raw {@code metric_sample} rows, purged past the retention
 * policy (ADR-0006). A batch drops one daily partition whose whole day is older than the cutoff
 * (ADR-0193); the partitions are created ahead by the partition maintainer.
 */
@Component
public class MetricSampleStore implements HousekeepingContributor, ManagedStore {

    public static final String ID = "metrics";

    private static final String TABLE = "metric_sample";

    /** Insert-only history: full pages, vacuumed by inserts so the visibility map stays current. */
    static final PartitionSpec PARTITIONS = new PartitionSpec(
            TABLE,
            "ts",
            Grain.DAY,
            7,
            "fillfactor=100, autovacuum_vacuum_insert_scale_factor=0, autovacuum_vacuum_insert_threshold=10000,"
                    + " autovacuum_analyze_scale_factor=0.05");

    private static final StoreDef DEF = new StoreDef(
            ID,
            "Metrics",
            List.of(TABLE),
            QuotaUnit.BYTES,
            Duration.ofDays(7),
            Duration.ofDays(1),
            Duration.ofDays(90));

    private final JdbcTemplate jdbc;
    private final PartitionDrops drops;

    public MetricSampleStore(JdbcTemplate jdbc, PartitionDrops drops) {
        this.jdbc = jdbc;
        this.drops = drops;
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
        return LifecycleSql.usage(jdbc, List.of(TABLE));
    }

    @Override
    public PurgeEstimate preview(Instant cutoff) {
        return drops.preview(PARTITIONS, cutoff);
    }

    @Override
    public long purgeBatch(Instant cutoff, int limit) {
        return drops.dropOldest(PARTITIONS, cutoff);
    }
}
