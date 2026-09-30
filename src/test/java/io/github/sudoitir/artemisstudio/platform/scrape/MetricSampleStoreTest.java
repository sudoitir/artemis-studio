package io.github.sudoitir.artemisstudio.platform.scrape;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link MetricSampleStore} against a real Postgres: a preview counts what is past the cutoff and
 * deletes nothing, and repeated bounded purges drop the expired daily partitions and the old rows
 * in the default partition, and nothing newer, until a call returns 0.
 */
class MetricSampleStoreTest extends PostgresIntegrationTest {

    private static final DateTimeFormatter SUFFIX = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Autowired
    MetricSampleStore store;

    @Autowired
    JdbcTemplate jdbc;

    private final UUID clusterId = UUID.randomUUID();
    private final UUID nodeId = UUID.randomUUID();
    private final Instant cutoff = Instant.now().minus(Duration.ofDays(7));

    @BeforeEach
    void emptyWhatIsExpired() {
        while (store.purgeBatch(cutoff, 1000) > 0) {
            // drain leftovers of other tests, so the counts below are exact
        }
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM metric_sample WHERE cluster_id = ?", clusterId);
    }

    private void partition(int daysAgo) {
        LocalDate day = LocalDate.now().minusDays(daysAgo);
        jdbc.execute(
                "CREATE TABLE IF NOT EXISTS metric_sample_%s PARTITION OF metric_sample FOR VALUES FROM ('%s') TO ('%s')"
                        .formatted(day.format(SUFFIX), day, day.plusDays(1)));
    }

    private void samples(int daysAgo, int count) {
        for (int i = 0; i < count; i++) {
            jdbc.update("""
                    INSERT INTO metric_sample (ts, value, subject_type, subject_name, metric, cluster_id, node_id)
                    VALUES (?, 1.0, 'QUEUE', 'Q', 'messageCount', ?, ?)
                    """, Timestamp.from(Instant.now().minus(Duration.ofDays(daysAgo))), clusterId, nodeId);
        }
    }

    private long mine() {
        return jdbc.queryForObject("SELECT count(*) FROM metric_sample WHERE cluster_id = ?", Long.class, clusterId);
    }

    private List<String> partitions() {
        return jdbc.queryForList("""
                SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
                 WHERE i.inhparent = 'metric_sample'::regclass AND c.relname ~ '^metric_sample_[0-9]{8}$'
                """, String.class);
    }

    @Test
    void previewCountsWhatIsPastTheCutoffAndDeletesNothing() {
        partition(30);
        samples(30, 3); // in the expired partition
        samples(20, 4); // no partition for that day: the default partition
        samples(2, 2); // recent

        assertThat(store.preview(cutoff).rows()).isEqualTo(7);
        assertThat(mine()).isEqualTo(9);
        assertThat(partitions())
                .contains("metric_sample_" + LocalDate.now().minusDays(30).format(SUFFIX));
    }

    @Test
    void repeatedBatchesRemoveOnlyWhatIsPastTheCutoffAndEndAtZero() {
        partition(30);
        partition(10);
        samples(30, 3);
        samples(10, 2);
        samples(20, 5);
        samples(2, 2);

        long calls = 0;
        long removed;
        do {
            removed = store.purgeBatch(cutoff, 2);
            calls++;
        } while (removed > 0 && calls < 20);

        assertThat(removed).isZero();
        assertThat(mine()).isEqualTo(2);
        assertThat(partitions())
                .doesNotContain(
                        "metric_sample_" + LocalDate.now().minusDays(30).format(SUFFIX),
                        "metric_sample_" + LocalDate.now().minusDays(10).format(SUFFIX));
        // two partitions dropped, three default batches of at most 2, then the empty one
        assertThat(calls).isEqualTo(6);
        assertThat(store.preview(cutoff).rows()).isZero();
        assertThat(store.usage().bytes()).isPositive();
    }
}
