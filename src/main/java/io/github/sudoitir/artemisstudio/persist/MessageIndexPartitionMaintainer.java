package io.github.sudoitir.artemisstudio.persist;

import io.github.sudoitir.artemisstudio.sql.QueueNamePattern;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Daily partition lifecycle for {@code message_index} (ADR-0059). The same problem
 * and the same maneuver as {@link MetricPartitionMaintainer}: create tomorrow's
 * partition before rows need it, and reclaim expired payload by dropping whole
 * ranges rather than deleting rows one at a time.
 *
 * <p>Retention differs from metrics in one way that matters. Each subscription
 * carries its own retention, and a partition holds rows from every subscription, so
 * a partition can only be dropped once it is older than the <em>longest</em>
 * retention in the estate. A subscription with a shorter window would otherwise keep
 * its payload for someone else's retention, so rows that outlive their own
 * subscription are removed by a bulk range delete first — still set-based, still one
 * statement per subscription, and it is the only way a per-subscription promise can
 * be kept on a shared partition.
 *
 * <p>When there is no subscription at all the index is dead weight: every partition
 * is expired and the whole thing drains away without an operator asking.
 */
@Component
@Slf4j
public class MessageIndexPartitionMaintainer {

    private static final int CREATE_AHEAD_DAYS = 3;
    private static final int NO_SUBSCRIPTION_RETENTION_DAYS = 1;
    private static final java.time.format.DateTimeFormatter SUFFIX =
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd");

    private final NamedParameterJdbcTemplate jdbc;
    private final MessageIndexSubscriptionRepository subscriptions;
    private final QueueSnapshotRepository snapshots;

    public MessageIndexPartitionMaintainer(
            NamedParameterJdbcTemplate jdbc,
            MessageIndexSubscriptionRepository subscriptions,
            QueueSnapshotRepository snapshots) {
        this.jdbc = jdbc;
        this.subscriptions = subscriptions;
        this.snapshots = snapshots;
    }

    /** Scheduled by {@code DynamicSchedules} on the partition-maintenance cron. Idempotent. */
    public void maintain() {
        createAhead();
        expirePerSubscription();
        dropExpired();
    }

    /**
     * Each day's table is created bare, filled from whatever the default partition
     * already holds for that range, and only then attached — the standard
     * split-the-default maneuver. Creating it directly as {@code PARTITION OF ... FOR
     * VALUES} fails the moment a row for that day has already landed in the default
     * partition, which is every day, because new rows route there until a matching
     * partition exists.
     */
    private void createAhead() {
        LocalDate today = LocalDate.now();
        for (int i = 0; i <= CREATE_AHEAD_DAYS; i++) {
            LocalDate day = today.plusDays(i);
            String name = "message_index_" + day.format(SUFFIX);
            if (partitionExists(name)) {
                continue;
            }
            LocalDate next = day.plusDays(1);
            jdbc.getJdbcTemplate().execute("CREATE TABLE %s (LIKE message_index INCLUDING ALL)".formatted(name));
            // The same storage parameters changeset 021 sets on message_index_default:
            // insert-heavy with one update per re-observation, so leave HOT space and
            // analyze often enough that the planner keeps choosing the GIN indexes.
            jdbc.getJdbcTemplate().execute("""
                            ALTER TABLE %s SET (
                                fillfactor = 90,
                                autovacuum_vacuum_scale_factor = 0.05,
                                autovacuum_analyze_scale_factor = 0.02)
                            """.formatted(name));
            jdbc.getJdbcTemplate().execute("""
                            WITH moved AS (
                                DELETE FROM message_index_default
                                 WHERE observed_at >= '%s' AND observed_at < '%s' RETURNING *
                            )
                            INSERT INTO %s SELECT * FROM moved
                            """.formatted(day, next, name));
            jdbc.getJdbcTemplate()
                    .execute("ALTER TABLE message_index ATTACH PARTITION %s FOR VALUES FROM ('%s') TO ('%s')"
                            .formatted(name, day, next));
        }
    }

    private boolean partitionExists(String name) {
        Boolean exists = jdbc.getJdbcTemplate().queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM pg_inherits i
                      JOIN pg_class c ON c.oid = i.inhrelid
                     WHERE i.inhparent = 'message_index'::regclass AND c.relname = '%s'
                )
                """.formatted(name), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * Remove what has outlived its own subscription while its partition is still
     * needed by a longer-retention one.
     *
     * <p>Retention belongs to a queue, not to a subscription: two subscriptions can
     * match the same queue, and the longer of them is the promise that queue is kept
     * under. So the queues are resolved in Java — the pattern is an Artemis wildcard,
     * not a SQL {@code LIKE} — grouped by the longest retention that claims them, and
     * deleted one range at a time. A queue held for the estate's longest window is
     * skipped entirely; {@link #dropExpired()} reclaims it by dropping the partition.
     */
    private void expirePerSubscription() {
        int longest = longestRetentionDays();
        for (UUID clusterId : subscriptions.findAll().stream()
                .map(MessageIndexSubscriptionEntity::getClusterId)
                .distinct()
                .toList()) {
            Map<Integer, List<String>> byRetention = queuesByRetention(clusterId);
            byRetention.forEach((retentionDays, queues) -> {
                if (retentionDays >= longest || queues.isEmpty()) {
                    return;
                }
                Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
                String placeholders = String.join(", ", java.util.Collections.nCopies(queues.size(), "?"));
                List<Object> binds = new ArrayList<>();
                binds.add(clusterId);
                binds.add(java.sql.Timestamp.from(cutoff));
                binds.addAll(queues);
                int removed = jdbc.getJdbcTemplate()
                        .update(
                                "DELETE FROM message_index WHERE cluster_id = ? AND observed_at < ? AND queue_name IN ("
                                        + placeholders + ')',
                                binds.toArray());
                if (removed > 0) {
                    log.info("Expired {} indexed messages past their {}-day retention", removed, retentionDays);
                }
            });
        }
    }

    /** Every captured queue in one cluster, grouped by the longest retention claiming it. */
    private Map<Integer, List<String>> queuesByRetention(UUID clusterId) {
        List<MessageIndexSubscriptionEntity> ofCluster = subscriptions.findByClusterId(clusterId);
        Map<Integer, List<String>> byRetention = new LinkedHashMap<>();
        snapshots.findByClusterId(clusterId).stream()
                .map(QueueSnapshotEntity::getQueueName)
                .distinct()
                .forEach(queue -> ofCluster.stream()
                        .filter(s -> QueueNamePattern.matches(s.getQueuePattern(), queue))
                        .mapToInt(MessageIndexSubscriptionEntity::getRetentionDays)
                        .max()
                        .ifPresent(retention -> byRetention
                                .computeIfAbsent(retention, k -> new ArrayList<>())
                                .add(queue)));
        return byRetention;
    }

    private int longestRetentionDays() {
        return subscriptions.findAll().stream()
                .mapToInt(MessageIndexSubscriptionEntity::getRetentionDays)
                .max()
                .orElse(NO_SUBSCRIPTION_RETENTION_DAYS);
    }

    private void dropExpired() {
        LocalDate cutoff = LocalDate.now().minusDays(longestRetentionDays());
        // The default partition is never dropped, so expired rows that landed there
        // — everything written before this ever ran, and anything stamped further
        // ahead than partitions exist for — are deleted rather than kept forever.
        jdbc.getJdbcTemplate()
                .update("DELETE FROM message_index_default WHERE observed_at < ?", java.sql.Date.valueOf(cutoff));
        List<String> partitions = jdbc.getJdbcTemplate().queryForList("""
                        SELECT c.relname FROM pg_inherits i
                          JOIN pg_class c ON c.oid = i.inhrelid
                          JOIN pg_class p ON p.oid = i.inhparent
                         WHERE p.relname = 'message_index'
                           AND c.relname ~ '^message_index_[0-9]{8}$'
                        """, String.class);
        for (String name : partitions) {
            LocalDate day = LocalDate.parse(name.substring("message_index_".length()), SUFFIX);
            if (!day.plusDays(1).isAfter(cutoff)) {
                // Plain DETACH, not CONCURRENTLY: Postgres refuses a concurrent detach
                // on a partitioned table that keeps a DEFAULT partition, and this one
                // does. The catalog update takes ACCESS EXCLUSIVE for milliseconds and
                // scans no rows.
                jdbc.getJdbcTemplate().execute("ALTER TABLE message_index DETACH PARTITION %s".formatted(name));
                jdbc.getJdbcTemplate().execute("DROP TABLE %s".formatted(name));
                log.info("Dropped expired message_index partition {}", name);
            }
        }
    }
}
