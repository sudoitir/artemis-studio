package io.github.sudoitir.artemisstudio.platform.scrape;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@link MetricPartitionMaintainer} against a real Postgres: create-ahead makes
 * today's and future partitions, and a partition that has fully aged past
 * retention is dropped without blocking a concurrent insert (design.md, Decision 4).
 */
class MetricPartitionMaintainerTest extends PostgresIntegrationTest {

    private static final DateTimeFormatter SUFFIX = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Autowired
    MetricPartitionMaintainer maintainer;

    @Autowired
    MetricSampleReaper reaper;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    private final UUID clusterId = UUID.randomUUID();
    private final UUID nodeId = UUID.randomUUID();

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM metric_sample WHERE cluster_id = :c", Map.of("c", clusterId));
    }

    private List<String> partitionNames() {
        return jdbc.getJdbcTemplate().queryForList("""
                        SELECT c.relname FROM pg_inherits i
                          JOIN pg_class c ON c.oid = i.inhrelid
                          JOIN pg_class p ON p.oid = i.inhparent
                         WHERE p.relname = 'metric_sample'
                        """, String.class);
    }

    @Test
    void createsAheadPartitionsForTodayAndFollowingDays() {
        maintainer.maintainNow();

        List<String> names = partitionNames();
        LocalDate today = LocalDate.now();
        for (int i = 0; i <= 3; i++) {
            assertThat(names).contains("metric_sample_" + today.plusDays(i).format(SUFFIX));
        }
    }

    @Test
    void attachesADayWhoseRowsAreAlreadyInTheDefaultPartition() {
        // Rows for a day always land in the default partition until that day has one of
        // its own, so every real run has rows to move. A run that cannot attach leaves
        // the day unpartitioned and every later run fails the same way — the state a
        // long-running instance was found in: "updated partition constraint for default
        // partition would be violated by some row".
        LocalDate day = LocalDate.now().plusDays(2);
        String name = "metric_sample_" + day.format(SUFFIX);
        jdbc.getJdbcTemplate().execute("DROP TABLE IF EXISTS %s".formatted(name));
        jdbc.update("""
                INSERT INTO metric_sample (ts, value, subject_type, subject_name, metric, cluster_id, node_id)
                VALUES (:ts, 2.0, 'QUEUE', 'Q', 'messageCount', :c, :n)
                """, Map.of("ts", java.sql.Timestamp.valueOf(day.atTime(9, 0)), "c", clusterId, "n", nodeId));

        maintainer.maintainNow();

        assertThat(partitionNames()).contains(name);
        Integer moved = jdbc.getJdbcTemplate().queryForObject("SELECT count(*) FROM %s".formatted(name), Integer.class);
        assertThat(moved).isEqualTo(1);
    }

    @Test
    void dropsAnExpiredPartitionWithoutBlockingAConcurrentInsert() throws InterruptedException {
        LocalDate old = LocalDate.now().minusDays(30);
        String name = "metric_sample_" + old.format(SUFFIX);
        jdbc.getJdbcTemplate()
                .execute("CREATE TABLE %s PARTITION OF metric_sample FOR VALUES FROM ('%s') TO ('%s')"
                        .formatted(name, old, old.plusDays(1)));

        reaper.setRetentionDays(7);
        maintainer.maintainNow();

        assertThat(partitionNames()).doesNotContain(name);

        // A fresh insert (routes into today's partition) must still succeed right after the drop.
        jdbc.update("""
                INSERT INTO metric_sample (ts, value, subject_type, subject_name, metric, cluster_id, node_id)
                VALUES (now(), 1.0, 'QUEUE', 'Q', 'messageCount', :c, :n)
                """, Map.of("c", clusterId, "n", nodeId));
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM metric_sample WHERE cluster_id = :c", Map.of("c", clusterId), Integer.class);
        assertThat(count).isEqualTo(1);
    }
}
