package io.github.sudoitir.rahdar.feature.rr;

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

/**
 * Request-reply flows under the data lifecycle (ADR-0134). {@code rr_event} is partitioned by day and has
 * no foreign key to its flow (ADR-0193): its events go when their day's partition is older than the
 * cutoff, so an event may outlive its flow by up to a day, and readers reach events through the flow.
 */
class RrFlowStore implements ManagedStore {

    private static final String TABLE = "rr_flow";
    private static final String OLD = "requested_at < ?";

    /** Stamped with the writer's clock, never a broker's, so a skewed broker cannot miss a partition. */
    static final PartitionSpec EVENT_PARTITIONS = new PartitionSpec(
            "rr_event",
            "ts",
            Grain.DAY,
            7,
            "fillfactor=100, autovacuum_vacuum_insert_scale_factor=0, autovacuum_vacuum_insert_threshold=5000");

    private static final StoreDef DEF = new StoreDef(
            "rr-flows",
            "Request-reply flows",
            List.of(TABLE, "rr_event"),
            QuotaUnit.ROWS,
            Duration.ofDays(7),
            Duration.ofDays(1),
            Duration.ofDays(90));

    private final JdbcTemplate jdbc;
    private final PartitionDrops drops;

    RrFlowStore(JdbcTemplate jdbc, PartitionDrops drops) {
        this.jdbc = jdbc;
        this.drops = drops;
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
        PurgeEstimate flows = LifecycleSql.estimate(jdbc, TABLE, OLD, cutoff);
        PurgeEstimate events = drops.preview(EVENT_PARTITIONS, cutoff);
        return new PurgeEstimate(flows.rows() + events.rows(), flows.bytes() + events.bytes());
    }

    @Override
    public long purgeBatch(Instant cutoff, int limit) {
        long events = drops.dropOldest(EVENT_PARTITIONS, cutoff);
        if (events > 0) {
            return events;
        }
        return LifecycleSql.deleteBatch(jdbc, TABLE, OLD, cutoff, limit);
    }
}
