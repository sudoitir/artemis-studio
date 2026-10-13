package io.github.sudoitir.artemisstudio.kernel.lifecycle.partition;

import io.github.sudoitir.artemisstudio.kernel.core.StatementTimeout;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.PurgeEstimate;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.partition.PartitionCatalog.Child;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Retention for partitioned history: drops a partition once everything in it is older than the cutoff
 * (ADR-0193). A store calls it from {@code purgeBatch}, so the housekeeper and the store SPI stay as
 * they are. A partition is detached with {@code CONCURRENTLY}, which blocks no writer, and then dropped.
 */
@Component
@Slf4j
public class PartitionDrops {

    private final JdbcTemplate jdbc;

    PartitionDrops(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The partitions whose whole period ended at or before {@code cutoff}, oldest first. */
    public List<String> expired(PartitionSpec spec, Instant cutoff) {
        return PartitionCatalog.children(jdbc, spec).stream()
                .filter(c -> c.start() != null && !c.detachPending())
                .filter(c -> !end(spec, c).isAfter(cutoff))
                .map(Child::name)
                .toList();
    }

    /** Rows and bytes in the partitions {@link #dropOldest} would drop; the rows are counted, in a long transaction. */
    public PurgeEstimate preview(PartitionSpec spec, Instant cutoff) {
        return StatementTimeout.inLongTransaction(jdbc, () -> {
            long rows = 0;
            long bytes = 0;
            for (String name : expired(spec, cutoff)) {
                // Names come from the catalog, not from a caller: see expired.
                rows += jdbc.queryForObject("SELECT count(*) FROM " + name, Long.class);
                bytes += jdbc.queryForObject("SELECT pg_total_relation_size(to_regclass(?))", Long.class, name);
            }
            return new PurgeEstimate(rows, bytes);
        });
    }

    /**
     * Drops the oldest expired partition, after finishing any detach a crash left pending.
     *
     * @return the rows it held (the planner's estimate), at least 1 when something was dropped, and 0
     *     when nothing was due
     */
    public long dropOldest(PartitionSpec spec, Instant cutoff) {
        long dropped = 0;
        for (Child pending : PartitionCatalog.children(jdbc, spec)) {
            if (pending.detachPending()) {
                dropped += finish(spec, pending.name());
            }
        }
        List<String> expired = expired(spec, cutoff);
        if (!expired.isEmpty()) {
            dropped += drop(spec, expired.get(0));
        }
        return dropped;
    }

    /** Detaches {@code name} without blocking writers and drops it. Must not run inside a transaction. */
    public long drop(PartitionSpec spec, String name) {
        long rows = PartitionCatalog.rows(jdbc, name);
        PartitionCatalog.outsideTransaction(
                jdbc,
                "ALTER TABLE " + spec.table() + " DETACH PARTITION " + name + " CONCURRENTLY",
                "DROP TABLE " + name);
        log.info("Dropped expired {} partition {}", spec.table(), name);
        return Math.max(1, rows);
    }

    private long finish(PartitionSpec spec, String name) {
        long rows = PartitionCatalog.rows(jdbc, name);
        PartitionCatalog.outsideTransaction(
                jdbc, "ALTER TABLE " + spec.table() + " DETACH PARTITION " + name + " FINALIZE", "DROP TABLE " + name);
        log.info("Finished the detach of {} partition {} and dropped it", spec.table(), name);
        return Math.max(1, rows);
    }

    private static Instant end(PartitionSpec spec, Child child) {
        return spec.grain().next(child.start()).atStartOfDay().toInstant(ZoneOffset.UTC);
    }
}
