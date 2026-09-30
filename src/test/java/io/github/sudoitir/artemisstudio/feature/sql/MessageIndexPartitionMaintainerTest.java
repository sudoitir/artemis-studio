package io.github.sudoitir.artemisstudio.feature.sql;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Partition maintenance against a real PostgreSQL, because every interesting part of
 * it is Postgres behaviour: splitting the default partition. Reclaiming payload by
 * dropping a range is {@link MessageIndexStoreTest}.
 */
class MessageIndexPartitionMaintainerTest extends PostgresIntegrationTest {

    @Autowired
    private MessageIndexPartitionMaintainer maintainer;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void createsTodayAndTheDaysAheadOfIt() {
        maintainer.maintain();

        List<String> partitions = jdbc.queryForList("""
                SELECT c.relname FROM pg_inherits i
                  JOIN pg_class c ON c.oid = i.inhrelid
                  JOIN pg_class p ON p.oid = i.inhparent
                 WHERE p.relname = 'message_index' AND c.relname ~ '^message_index_[0-9]{8}$'
                """, String.class);

        // Created ahead, so a missed run never turns into a failed insert.
        assertThat(partitions)
                .contains("message_index_"
                        + LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")))
                .hasSizeGreaterThanOrEqualTo(4);
    }
}
