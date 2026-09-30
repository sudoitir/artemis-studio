package io.github.sudoitir.artemisstudio.feature.sql;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.feature.sql.QueryAst.Source;
import io.github.sudoitir.artemisstudio.feature.sql.QueryResult.Row;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link MessageIndexStore} against a real Postgres: a preview counts what is past the cutoff and
 * deletes nothing, and repeated bounded purges drop the expired daily partitions and the old rows
 * in the default partition, and nothing newer, until a call returns 0.
 */
class MessageIndexStoreTest extends PostgresIntegrationTest {

    private static final DateTimeFormatter SUFFIX = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Autowired
    MessageIndexStore store;

    @Autowired
    MessageIndexWriter writer;

    @Autowired
    JdbcTemplate jdbc;

    private final UUID clusterId = UUID.randomUUID();
    private final Instant cutoff = Instant.now().minus(Duration.ofDays(7));
    private long nextId = 1;

    @BeforeEach
    void emptyWhatIsExpired() {
        while (store.purgeBatch(cutoff, 1000) > 0) {
            // drain leftovers of other tests, so the counts below are exact
        }
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM message_index WHERE cluster_id = ?", clusterId);
    }

    private Row message(long id) {
        return new Row(
                UUID.randomUUID(),
                "primary",
                "ORDER.IN",
                "ORDER.IN",
                id,
                3,
                true,
                4,
                0,
                0,
                0,
                null,
                null,
                null,
                null,
                null,
                "body",
                false,
                false,
                null,
                Map.of(),
                Source.BROKER,
                null,
                null,
                null,
                null);
    }

    private void partition(int daysAgo) {
        LocalDate day = LocalDate.now().minusDays(daysAgo);
        jdbc.execute(
                "CREATE TABLE IF NOT EXISTS message_index_%s PARTITION OF message_index FOR VALUES FROM ('%s') TO ('%s')"
                        .formatted(day.format(SUFFIX), day, day.plusDays(1)));
    }

    private void messages(int daysAgo, int count) {
        for (int i = 0; i < count; i++) {
            writer.observe(clusterId, message(nextId++), Instant.now().minus(Duration.ofDays(daysAgo)));
        }
    }

    private long mine() {
        return jdbc.queryForObject("SELECT count(*) FROM message_index WHERE cluster_id = ?", Long.class, clusterId);
    }

    private List<String> partitions() {
        return jdbc.queryForList("""
                SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
                 WHERE i.inhparent = 'message_index'::regclass AND c.relname ~ '^message_index_[0-9]{8}$'
                """, String.class);
    }

    @Test
    void previewCountsWhatIsPastTheCutoffAndDeletesNothing() {
        partition(30);
        messages(30, 3); // in the expired partition
        messages(20, 4); // no partition for that day: the default partition
        messages(2, 2); // recent

        assertThat(store.preview(cutoff).rows()).isEqualTo(7);
        assertThat(mine()).isEqualTo(9);
        assertThat(partitions())
                .contains("message_index_" + LocalDate.now().minusDays(30).format(SUFFIX));
    }

    @Test
    void repeatedBatchesRemoveOnlyWhatIsPastTheCutoffAndEndAtZero() {
        partition(30);
        partition(10);
        messages(30, 3);
        messages(10, 2);
        messages(20, 5);
        messages(2, 2);

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
                        "message_index_" + LocalDate.now().minusDays(30).format(SUFFIX),
                        "message_index_" + LocalDate.now().minusDays(10).format(SUFFIX));
        // two partitions dropped, three default batches of at most 2, then the empty one
        assertThat(calls).isEqualTo(6);
        assertThat(store.preview(cutoff).rows()).isZero();
        assertThat(store.usage().bytes()).isPositive();
    }
}
