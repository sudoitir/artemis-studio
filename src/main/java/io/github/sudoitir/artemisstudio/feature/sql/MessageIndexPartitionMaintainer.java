package io.github.sudoitir.artemisstudio.feature.sql;

import io.github.sudoitir.artemisstudio.feature.sql.internal.persistence.MessageIndexSubscriptionEntity;
import io.github.sudoitir.artemisstudio.feature.sql.internal.persistence.MessageIndexSubscriptionRepository;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.LifecycleRegistry;
import io.github.sudoitir.artemisstudio.platform.scrape.MetricPartitionMaintainer;
import io.github.sudoitir.artemisstudio.platform.scrape.QueueSnapshot;
import io.github.sudoitir.artemisstudio.platform.scrape.QueueSnapshots;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Daily partition maintenance for {@code message_index} (ADR-0059). The same problem
 * and the same maneuver as {@link MetricPartitionMaintainer}: create tomorrow's
 * partition before rows need it. Reclaiming what has outlived the store's retention,
 * which caps every subscription's, is {@link MessageIndexStore}'s purge (ADR-0134).
 *
 * <p>Each subscription may carry a shorter retention than the store, and a partition
 * holds rows from every subscription, so the store cannot honour that by dropping a
 * partition. Rows that outlive their own subscription are removed here by a bulk range
 * delete — still set-based, still one statement per retention — which is the only way a
 * per-subscription promise can be kept on a shared partition.
 */
@Component
@Slf4j
public class MessageIndexPartitionMaintainer {

    private static final int CREATE_AHEAD_DAYS = 3;
    private static final java.time.format.DateTimeFormatter SUFFIX =
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd");

    private final NamedParameterJdbcTemplate jdbc;
    private final MessageIndexSubscriptionRepository subscriptions;
    private final QueueSnapshots snapshots;
    private final CaptureAddresses captureAddresses;
    private final LifecycleRegistry lifecycle;
    private final org.springframework.transaction.support.TransactionTemplate transactions;

    public MessageIndexPartitionMaintainer(
            NamedParameterJdbcTemplate jdbc,
            MessageIndexSubscriptionRepository subscriptions,
            QueueSnapshots snapshots,
            CaptureAddresses captureAddresses,
            LifecycleRegistry lifecycle,
            org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.subscriptions = subscriptions;
        this.snapshots = snapshots;
        this.captureAddresses = captureAddresses;
        this.lifecycle = lifecycle;
        this.transactions = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    }

    /** Scheduled by {@code JobScheduler} on the partition-maintenance cron. Idempotent. */
    public void maintain() {
        createAhead();
        expirePerSubscription();
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
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
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
            // One transaction: the attach takes its lock with the move, so a row for that day
            // inserted into the default partition between the two can no longer make the
            // attach fail its validation scan.
            // Identifiers cannot be bound. The name is "message_index_" plus a formatted date,
            // and day and next are LocalDates, so nothing here carries caller-supplied text.
            String move = """
                    WITH moved AS (
                        DELETE FROM message_index_default
                         WHERE observed_at >= '%s' AND observed_at < '%s' RETURNING *
                    )
                    INSERT INTO %s SELECT * FROM moved
                    """.formatted(day, next, name);
            String attach = "ALTER TABLE message_index ATTACH PARTITION %s FOR VALUES FROM ('%s') TO ('%s')"
                    .formatted(name, day, next);
            transactions.executeWithoutResult(status -> {
                jdbc.getJdbcTemplate().execute(move);
                jdbc.getJdbcTemplate().execute(attach);
            });
        }
    }

    private boolean partitionExists(String name) {
        Boolean exists = jdbc.getJdbcTemplate().queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM pg_inherits i
                      JOIN pg_class c ON c.oid = i.inhrelid
                     WHERE i.inhparent = 'message_index'::regclass AND c.relname = ?
                )
                """, Boolean.class, name);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * Remove what has outlived its own subscription while its partition is still
     * needed by a longer-retention one, or by the store's own retention.
     *
     * <p>Retention belongs to a queue, not to a subscription: two subscriptions can
     * match the same queue, and the longer of them is the promise that queue is kept
     * under. So the queues are resolved in Java — the pattern is an Artemis wildcard,
     * not a SQL {@code LIKE} — grouped by the longest retention that claims them, and
     * deleted one range at a time. A queue held for the store's whole retention is
     * skipped entirely; {@link MessageIndexStore} reclaims it by dropping the partition.
     */
    private void expirePerSubscription() {
        int storeDays =
                (int) lifecycle.retention(MessageIndexStore.ID).orElseThrow().toDays();
        for (UUID clusterId : subscriptions.findAll().stream()
                .map(MessageIndexSubscriptionEntity::getClusterId)
                .distinct()
                .toList()) {
            Map<Integer, List<String>> byRetention = queuesByRetention(clusterId);
            byRetention.forEach((retentionDays, queues) -> {
                if (retentionDays >= storeDays || queues.isEmpty()) {
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

    /**
     * Every name rows are stored under in one cluster, grouped by the longest retention claiming
     * it. A sampled row is stored under its queue; a captured row under its address, which is
     * not the queue name for a multicast address — so both are claimed, or captured payload
     * would be kept past its subscription's retention.
     */
    private Map<Integer, List<String>> queuesByRetention(UUID clusterId) {
        List<MessageIndexSubscriptionEntity> ofCluster = subscriptions.findByClusterId(clusterId);
        Map<String, Integer> longest = new LinkedHashMap<>();
        snapshots.forCluster(clusterId).stream()
                .map(QueueSnapshot::queueName)
                .distinct()
                .forEach(queue -> ofCluster.stream()
                        .filter(s -> QueueNamePattern.matches(s.getQueuePattern(), queue))
                        .mapToInt(MessageIndexSubscriptionEntity::getRetentionDays)
                        .max()
                        .ifPresent(retention -> longest.merge(queue, retention, Math::max)));
        for (MessageIndexSubscriptionEntity subscription : ofCluster) {
            if (subscription.getMode() == CaptureMode.CAPTURE) {
                for (String address : captureAddresses.of(clusterId, subscription)) {
                    longest.merge(address, subscription.getRetentionDays(), Math::max);
                }
            }
        }
        Map<Integer, List<String>> byRetention = new LinkedHashMap<>();
        longest.forEach((name, retention) ->
                byRetention.computeIfAbsent(retention, k -> new ArrayList<>()).add(name));
        return byRetention;
    }
}
