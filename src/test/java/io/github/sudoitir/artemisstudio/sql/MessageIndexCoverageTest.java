package io.github.sudoitir.artemisstudio.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.persist.MessageIndexSubscriptionEntity;
import io.github.sudoitir.artemisstudio.persist.MessageIndexSubscriptionRepository;
import io.github.sudoitir.artemisstudio.sql.QueryPlan.Notice;
import io.github.sudoitir.artemisstudio.sql.QueryPlan.Target;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The index's failure mode is a quiet one: a short list that reads as the answer.
 * Every case here is a way the index cannot speak for what was asked, and the
 * assertion is that it says so.
 */
class MessageIndexCoverageTest {

    private static final UUID CLUSTER = UUID.randomUUID();

    private final SqlQueryParser parser = new SqlQueryParser();
    private MessageIndexSubscriptionRepository subscriptions;
    private MessageIndexCoverage coverage;

    @BeforeEach
    void setUp() {
        subscriptions = mock(MessageIndexSubscriptionRepository.class);
        coverage = new MessageIndexCoverage(
                subscriptions,
                mock(io.github.sudoitir.artemisstudio.persist.MessageCaptureNodeRepository.class),
                mock(io.github.sudoitir.artemisstudio.persist.QueueSnapshotRepository.class));
    }

    private MessageIndexSubscriptionEntity subscription(String pattern, Instant captureFrom, int retentionDays) {
        MessageIndexSubscriptionEntity entity = new MessageIndexSubscriptionEntity();
        entity.setId(UUID.randomUUID());
        entity.setClusterId(CLUSTER);
        entity.setQueuePattern(pattern);
        entity.setCaptureFrom(captureFrom);
        entity.setRetentionDays(retentionDays);
        entity.setIntervalMs(5000);
        entity.setCreatedAt(Instant.now());
        entity.setEnabled(true);
        return entity;
    }

    private Target target(String queueName) {
        return new Target(UUID.randomUUID(), "primary", queueName, queueName, "ANYCAST", 10, Instant.now(), true);
    }

    private QueryAst ast(String sql) {
        return parser.parse(sql);
    }

    @Test
    void namesTheQueueTheIndexCannotAnswerFor() {
        when(subscriptions.findByClusterId(CLUSTER))
                .thenReturn(List.of(subscription("ORDER.IN", Instant.now().minus(Duration.ofDays(3)), 7)));

        List<Notice> notices = coverage.check(
                CLUSTER, ast("SELECT * FROM index.\"ORDER.*\""), List.of(target("ORDER.IN"), target("ORDER.RETRY")));

        // Not "no results for ORDER.RETRY": the queue is named, because an operator
        // reading a wildcard result has no other way to learn one target was silent.
        assertThat(notices).anySatisfy(notice -> assertThat(notice.detail()).contains("ORDER.RETRY"));
        assertThat(notices).noneSatisfy(notice -> assertThat(notice.detail()).contains("ORDER.IN —"));
    }

    @Test
    void warnsWhenTheWindowReachesBeforeCaptureBegan() {
        when(subscriptions.findByClusterId(CLUSTER))
                .thenReturn(List.of(subscription("ORDER.IN", Instant.now().minus(Duration.ofHours(2)), 30)));

        List<Notice> notices = coverage.check(
                CLUSTER,
                ast("SELECT * FROM index.\"ORDER.IN\" WHERE timestamp > now() - interval '2 days'"),
                List.of(target("ORDER.IN")));

        assertThat(notices)
                .singleElement()
                .satisfies(notice -> assertThat(notice.kind()).isEqualTo(Notice.Kind.INDEX_COVERAGE_GAP));
    }

    @Test
    void warnsWhenTheWindowReachesPastRetention() {
        // Capturing for a year, but only keeping two days of it — the gap is retention,
        // not capture, and the operator is told either way.
        when(subscriptions.findByClusterId(CLUSTER))
                .thenReturn(List.of(subscription("ORDER.IN", Instant.now().minus(Duration.ofDays(365)), 2)));

        List<Notice> notices = coverage.check(
                CLUSTER,
                ast("SELECT * FROM index.\"ORDER.IN\" WHERE timestamp > now() - interval '10 days'"),
                List.of(target("ORDER.IN")));

        assertThat(notices).hasSize(1);
    }

    @Test
    void saysNothingWhenTheWindowIsFullyCovered() {
        when(subscriptions.findByClusterId(CLUSTER))
                .thenReturn(List.of(subscription("ORDER.#", Instant.now().minus(Duration.ofDays(30)), 30)));

        List<Notice> notices = coverage.check(
                CLUSTER,
                ast("SELECT * FROM index.\"ORDER.IN\" WHERE timestamp > now() - interval '2 hours'"),
                List.of(target("ORDER.IN")));

        assertThat(notices).isEmpty();
    }

    @Test
    void aDisabledSubscriptionCoversNothing() {
        MessageIndexSubscriptionEntity disabled =
                subscription("ORDER.IN", Instant.now().minus(Duration.ofDays(3)), 7);
        disabled.setEnabled(false);
        when(subscriptions.findByClusterId(CLUSTER)).thenReturn(List.of(disabled));

        assertThat(coverage.isIndexed(CLUSTER, "ORDER.IN")).isFalse();
        assertThat(coverage.check(CLUSTER, ast("SELECT * FROM index.\"ORDER.IN\""), List.of(target("ORDER.IN"))))
                .isNotEmpty();
    }
}
