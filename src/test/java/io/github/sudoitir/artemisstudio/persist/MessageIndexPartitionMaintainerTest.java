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

    @Autowired
    private MessageIndexSubscriptionRepository subscriptions;

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
                null,
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

    /**
     * Disabling a subscription pauses recording; it does not consent to what was
     * already captured being destroyed early. Retention is therefore computed over
     * every subscription that exists, not only the enabled ones — which is what
     * {@code findAll()} gives and what this test exists to keep giving. A refactor to
     * {@code findByEnabledTrue()} would look like a tidy-up and would silently shorten
     * a disabled subscription's payload to the no-subscription floor.
     */
    @Test
    void keepsWhatADisabledSubscriptionCaptured() {
        UUID clusterId = UUID.randomUUID();
        jdbc.update("INSERT INTO cluster (id, name) VALUES (?, ?)", clusterId, "disabled-retention");

        MessageIndexSubscriptionEntity subscription = new MessageIndexSubscriptionEntity();
        subscription.setId(UUID.randomUUID());
        subscription.setClusterId(clusterId);
        subscription.setQueuePattern("ORDER.IN");
        subscription.setIntervalMs(5000);
        subscription.setRetentionDays(30);
        subscription.setCaptureFrom(Instant.now().minus(Duration.ofDays(10)));
        subscription.setCreatedAt(Instant.now().minus(Duration.ofDays(10)));
        subscription.setEnabled(false);
        subscriptions.saveAndFlush(subscription);

        writer.observe(clusterId, message(11), Instant.now().minus(Duration.ofDays(5)));
        writer.observe(clusterId, message(12), Instant.now());

        maintainer.maintain();

        Long remaining =
                jdbc.queryForObject("SELECT count(*) FROM message_index WHERE cluster_id = ?", Long.class, clusterId);
        assertThat(remaining).isEqualTo(2);
    }
}
