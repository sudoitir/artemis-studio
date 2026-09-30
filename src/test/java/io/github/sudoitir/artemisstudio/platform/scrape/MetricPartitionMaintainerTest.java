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
 * today's and future partitions, moving the rows already waiting in the default partition.
 * Dropping expired partitions is {@link MetricSampleStoreTest}.
 */
class MetricPartitionMaintainerTest extends PostgresIntegrationTest {

    private static final DateTimeFormatter SUFFIX = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Autowired
    MetricPartitionMaintainer maintainer;

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
}
