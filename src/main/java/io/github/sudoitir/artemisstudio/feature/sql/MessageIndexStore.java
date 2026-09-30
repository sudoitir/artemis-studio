package io.github.sudoitir.artemisstudio.feature.sql;

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
 * The {@code message-index} store (ADR-0132): indexed and captured message payload in {@code
 * message_index}, purged past the store's retention, which caps every subscription's own (ADR-0059).
 * A batch drops one daily partition whose whole day is older than the cutoff, and once none is left
 * deletes from {@code message_index_default}, the catch-all for rows written before their day had a
 * partition. {@link MessageIndexPartitionMaintainer} creates the partitions ahead and trims what
 * outlives a shorter subscription retention.
 */
@Component
@Slf4j
public class MessageIndexStore implements HousekeepingContributor, ManagedStore {

    public static final String ID = "message-index";

    private static final String TABLE = "message_index";
    private static final String DEFAULT_PARTITION = "message_index_default";
    private static final String DEFAULT_PREDICATE = "observed_at < ?";
    private static final DateTimeFormatter SUFFIX = DateTimeFormatter.ofPattern("yyyyMMdd");
    /**
     * 90 days by default, the longest a subscription may keep: the store's retention caps every
     * subscription's, so a shorter default would silently cut short a subscription an operator set
     * to keep longer. Lowering it is the operator's call, with the preview showing what goes.
     */
    private static final StoreDef DEF = new StoreDef(
            ID,
            "Message index",
            List.of(TABLE),
            QuotaUnit.BYTES,
            Duration.ofDays(90),
            Duration.ofDays(1),
            Duration.ofDays(90));

    private final JdbcTemplate jdbc;

    public MessageIndexStore(JdbcTemplate jdbc) {
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
        log.info("Dropped expired message_index partition {}", name);
        return Math.max(1, rows);
    }

    /** The daily partitions whose whole day is at or before {@code cutoff}, oldest first. */
    private List<String> expiredPartitions(Instant cutoff) {
        return jdbc.queryForList("""
                        SELECT c.relname FROM pg_inherits i
                          JOIN pg_class c ON c.oid = i.inhrelid
                          JOIN pg_class p ON p.oid = i.inhparent
                         WHERE p.relname = 'message_index'
                           AND c.relname ~ '^message_index_[0-9]{8}$'
                         ORDER BY c.relname
                        """, String.class).stream()
                .filter(name -> {
                    LocalDate day = LocalDate.parse(name.substring("message_index_".length()), SUFFIX);
                    return !day.plusDays(1)
                            .atStartOfDay(ZoneId.systemDefault())
                            .toInstant()
                            .isAfter(cutoff);
                })
                .toList();
    }
}
