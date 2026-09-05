package io.github.sudoitir.artemisstudio.persist;

import java.time.LocalDate;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily partition lifecycle for {@code metric_sample} (ADR-0006, ADR-0033). Creates
 * today's partition plus a few days ahead so a missed run never causes an insert to
 * fail, and drops partitions once their entire date range is older than the
 * retention window.
 *
 * <p>Mirrors changeset {@code 012-metric-partitions.sql}'s bootstrap DO block —
 * that changeset only covers the moment migrations finish; this is the ongoing
 * schedule. Drops go through {@code DETACH CONCURRENTLY} then {@code DROP TABLE}
 * rather than {@code DROP TABLE} directly on an attached partition, which would
 * take an {@code ACCESS EXCLUSIVE} lock on the parent and stall concurrent scrape
 * inserts (design.md, Decision 4).
 */
@Component
@Slf4j
public class MetricPartitionMaintainer {

    private static final int CREATE_AHEAD_DAYS = 3;
    private static final java.time.format.DateTimeFormatter SUFFIX =
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd");

    private final NamedParameterJdbcTemplate jdbc;
    private final MetricSampleReaper reaper;

    public MetricPartitionMaintainer(NamedParameterJdbcTemplate jdbc, MetricSampleReaper reaper) {
        this.jdbc = jdbc;
        this.reaper = reaper;
    }

    /** Runs once shortly after the reaper, at a quiet hour. Both are idempotent. */
    @Scheduled(cron = "0 0 3 * * *")
    public void maintain() {
        createAhead();
        dropExpired();
    }

    /**
     * Creating today's partition directly as {@code PARTITION OF ... FOR VALUES}
     * only works while the default partition holds no row in that range yet. Any
     * day this runs even slightly after rows for "today" have already landed in
     * {@code metric_sample_default} — which is every day, since new rows always
     * route there until a matching partition exists — Postgres refuses an
     * overlapping partition outright. So each day's table is created bare,
     * populated with whatever the default partition already holds for that
     * range, and only then attached (the standard "split the default partition"
     * maneuver; mirrors changeset {@code 012-metric-partitions.sql}'s bootstrap).
     */
    private void createAhead() {
        LocalDate today = LocalDate.now();
        for (int i = 0; i <= CREATE_AHEAD_DAYS; i++) {
            LocalDate day = today.plusDays(i);
            String name = "metric_sample_" + day.format(SUFFIX);
            if (partitionExists(name)) {
                continue;
            }
            LocalDate next = day.plusDays(1);
            jdbc.getJdbcTemplate().execute("CREATE TABLE %s (LIKE metric_sample INCLUDING ALL)".formatted(name));
            jdbc.getJdbcTemplate().execute("""
                            ALTER TABLE %s SET (
                                fillfactor = 100,
                                autovacuum_vacuum_insert_scale_factor = 0.0,
                                autovacuum_vacuum_insert_threshold = 10000,
                                autovacuum_analyze_scale_factor = 0.05)
                            """.formatted(name));
            jdbc.getJdbcTemplate().execute("""
                            WITH moved AS (
                                DELETE FROM metric_sample_default WHERE ts >= '%s' AND ts < '%s' RETURNING *
                            )
                            INSERT INTO %s SELECT * FROM moved
                            """.formatted(day, next, name));
            jdbc.getJdbcTemplate()
                    .execute("ALTER TABLE metric_sample ATTACH PARTITION %s FOR VALUES FROM ('%s') TO ('%s')"
                            .formatted(name, day, next));
        }
    }

    private boolean partitionExists(String name) {
        Boolean exists = jdbc.getJdbcTemplate().queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM pg_inherits i
                      JOIN pg_class c ON c.oid = i.inhrelid
                     WHERE i.inhparent = 'metric_sample'::regclass AND c.relname = '%s'
                )
                """.formatted(name), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    private void dropExpired() {
        LocalDate cutoff = LocalDate.now().minusDays(reaper.retentionDays());
        List<String> partitions = jdbc.getJdbcTemplate().queryForList("""
                        SELECT c.relname FROM pg_inherits i
                          JOIN pg_class c ON c.oid = i.inhrelid
                          JOIN pg_class p ON p.oid = i.inhparent
                         WHERE p.relname = 'metric_sample'
                           AND c.relname ~ '^metric_sample_[0-9]{8}$'
                        """, String.class);
        for (String name : partitions) {
            LocalDate day = LocalDate.parse(name.substring("metric_sample_".length()), SUFFIX);
            if (!day.plusDays(1).isAfter(cutoff)) {
                // CONCURRENTLY is not an option here: Postgres refuses a concurrent
                // detach on a partitioned table that carries a DEFAULT partition
                // (kept permanently, see MetricSampleReaper), so this is a plain
                // synchronous DETACH. It still briefly takes ACCESS EXCLUSIVE on the
                // parent, but only for a catalog update — no row scan — so the hold
                // is on the order of milliseconds regardless of partition size.
                jdbc.getJdbcTemplate().execute("ALTER TABLE metric_sample DETACH PARTITION %s".formatted(name));
                jdbc.getJdbcTemplate().execute("DROP TABLE %s".formatted(name));
                log.info("Dropped expired metric_sample partition {}", name);
            }
        }
    }

    /** Test/ops hook — the settings-driven retention window can change without a restart. */
    public void maintainNow() {
        maintain();
    }
}
