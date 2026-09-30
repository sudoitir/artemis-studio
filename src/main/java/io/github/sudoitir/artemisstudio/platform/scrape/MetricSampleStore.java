package io.github.sudoitir.artemisstudio.platform.scrape;

import io.github.sudoitir.artemisstudio.kernel.lifecycle.HousekeepingContributor;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.LifecycleSql;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.ManagedStore;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.PurgeEstimate;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.StoreDef;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.StoreDef.QuotaUnit;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.StoreUsage;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The {@code metrics} store (ADR-0132): raw {@code metric_sample} rows, purged past the retention
 * policy (ADR-0006). A batch drops one daily partition whose whole day is older than the cutoff,
 * and once none is left deletes from {@code metric_sample_default}, the catch-all for rows written
 * before their day had a partition, which a date range can never drop. {@link
 * MetricPartitionMaintainer} only creates the partitions ahead.
 */
@Component
@Slf4j
public class MetricSampleStore implements HousekeepingContributor, ManagedStore {

    public static final String ID = "metrics";

    private static final String TABLE = "metric_sample";
    private static final String DEFAULT_PARTITION = "metric_sample_default";
    private static final String DEFAULT_PREDICATE = "ts < ?";
    private static final DateTimeFormatter SUFFIX = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final StoreDef DEF = new StoreDef(
            ID,
            "Metrics",
            List.of(TABLE),
            QuotaUnit.BYTES,
            Duration.ofDays(7),
            Duration.ofDays(1),
            Duration.ofDays(90));

    private final JdbcTemplate jdbc;

    public MetricSampleStore(JdbcTemplate jdbc) {
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
        return LifecycleSql.usage(jdbc, List.of(TABLE));
    }

    @Override
    public PurgeEstimate preview(Instant cutoff) {
        long rows = 0;
        long bytes = 0;
        for (String name : expiredPartitions(cutoff)) {
            rows += jdbc.queryForObject("SELECT count(*) FROM " + name, Long.class);
            bytes += jdbc.queryForObject("SELECT pg_total_relation_size(?::regclass)", Long.class, name);
        }
        PurgeEstimate inDefault = LifecycleSql.estimate(jdbc, DEFAULT_PARTITION, DEFAULT_PREDICATE, cutoff);
        return new PurgeEstimate(rows + inDefault.rows(), bytes + inDefault.bytes());
    }

    @Override
    public long purgeBatch(Instant cutoff, int limit) {
        List<String> expired = expiredPartitions(cutoff);
        if (expired.isEmpty()) {
            return LifecycleSql.deleteBatch(jdbc, DEFAULT_PARTITION, DEFAULT_PREDICATE, cutoff, limit);
        }
        String name = expired.get(0);
        long rows = LifecycleSql.usage(jdbc, List.of(name)).rows();
        // Plain DETACH, not CONCURRENTLY: Postgres refuses a concurrent detach on a partitioned
        // table that keeps a DEFAULT partition, and this one does. The catalog update takes
        // ACCESS EXCLUSIVE for milliseconds and scans no rows.
        jdbc.execute("ALTER TABLE " + TABLE + " DETACH PARTITION " + name);
        jdbc.execute("DROP TABLE " + name);
        log.info("Dropped expired metric_sample partition {}", name);
        return Math.max(1, rows);
    }

    /** The daily partitions whose whole day is at or before {@code cutoff}, oldest first. */
    private List<String> expiredPartitions(Instant cutoff) {
        return jdbc.queryForList("""
                        SELECT c.relname FROM pg_inherits i
                          JOIN pg_class c ON c.oid = i.inhrelid
                          JOIN pg_class p ON p.oid = i.inhparent
                         WHERE p.relname = 'metric_sample'
                           AND c.relname ~ '^metric_sample_[0-9]{8}$'
                         ORDER BY c.relname
                        """, String.class).stream()
                .filter(name -> {
                    LocalDate day = LocalDate.parse(name.substring("metric_sample_".length()), SUFFIX);
                    return !day.plusDays(1)
                            .atStartOfDay(ZoneId.systemDefault())
                            .toInstant()
                            .isAfter(cutoff);
                })
                .toList();
    }
}
