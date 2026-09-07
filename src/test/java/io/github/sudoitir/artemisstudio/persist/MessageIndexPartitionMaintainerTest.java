package io.github.sudoitir.artemisstudio.persist;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.sql.QueryAst.Source;
import io.github.sudoitir.artemisstudio.sql.QueryResult.Row;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Partition maintenance against a real PostgreSQL, because every interesting part of
 * it is Postgres behaviour: splitting the default partition, and reclaiming payload
 * by dropping a range rather than deleting rows.
 */
class MessageIndexPartitionMaintainerTest extends PostgresIntegrationTest {

    private static final UUID CLUSTER = UUID.randomUUID();

    @Autowired
    private MessageIndexPartitionMaintainer maintainer;

    @Autowired
    private MessageIndexWriter writer;

    @Autowired
    private JdbcTemplate jdbc;

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
                Map.of(),
                Source.BROKER,
                null,
                null);
    }

    @Test
    void createsTodayAndTheDaysAheadOfIt() {
        maintainer.maintain();

        List<String> partitions = jdbc.queryForList("""
                SELECT c.relname FROM pg_inherits i
                  JOIN pg_class c ON c.oid = i.inhrelid
                  JOIN pg_class p ON p.oid = i.inhparent
                 WHERE p.relname = 'message_index' AND c.relname ~ '^message_index_[0-9]{8}$'
                """, String.class);

        assertThat(partitions)
                .contains("message_index_"
                        + LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")));
        // Created ahead, so a missed run never turns into a failed insert.
        assertThat(partitions).hasSizeGreaterThanOrEqualTo(4);
    }

    @Test
    void reclaimsPayloadThatIsOlderThanAnySubscriptionKeeps() {
        // No subscription at all: the index is holding payload nobody asked for, and
        // maintenance drains it without an operator having to notice.
        writer.observe(CLUSTER, message(1), Instant.now().minus(Duration.ofDays(5)));
        writer.observe(CLUSTER, message(2), Instant.now());

        maintainer.maintain();

        Long remaining =
                jdbc.queryForObject("SELECT count(*) FROM message_index WHERE cluster_id = ?", Long.class, CLUSTER);
        assertThat(remaining).isEqualTo(1);
    }
}
