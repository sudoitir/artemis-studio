package io.github.sudoitir.artemisstudio.feature.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.feature.sql.QueryPlan.Notice;
import io.github.sudoitir.artemisstudio.feature.sql.QueryPlan.Target;
import io.github.sudoitir.artemisstudio.feature.sql.internal.persistence.MessageCaptureNodeEntity;
import io.github.sudoitir.artemisstudio.feature.sql.internal.persistence.MessageCaptureNodeRepository;
import io.github.sudoitir.artemisstudio.feature.sql.internal.persistence.MessageIndexSubscriptionEntity;
import io.github.sudoitir.artemisstudio.feature.sql.internal.persistence.MessageIndexSubscriptionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
    private MessageCaptureNodeRepository captureNodes;
    private MessageIndexCoverage coverage;

    @BeforeEach
    void setUp() {
        subscriptions = mock(MessageIndexSubscriptionRepository.class);
        captureNodes = mock(MessageCaptureNodeRepository.class);
        coverage = new MessageIndexCoverage(
                subscriptions,
                captureNodes,
                mock(io.github.sudoitir.artemisstudio.platform.scrape.QueueSnapshots.class));
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

        assertThat(coverage.isCaptured(CLUSTER, List.of(target("ORDER.IN")))).isFalse();
        assertThat(coverage.check(CLUSTER, ast("SELECT * FROM index.\"ORDER.IN\""), List.of(target("ORDER.IN"))))
                .isNotEmpty();
    }

    /**
     * Capture never backfills: it copies what is routed from {@code captureFrom} on. Measured
     * on the dev stack, a queue held 3 messages, a CAPTURE subscription was created, and the
     * plain query answered 0 rows from the index with nothing said. A query with no time window
     * asks for everything, so it is told where the index begins.
     */
    @Test
    void aQueryWithNoWindowIsToldTheIndexHoldsNothingBeforeCaptureBegan() {
        Instant began = Instant.now().minus(Duration.ofMinutes(5));
        MessageIndexSubscriptionEntity captured = subscription("ORDER.IN", began, 7);
        captured.setMode(CaptureMode.CAPTURE);
        when(subscriptions.findByClusterId(CLUSTER)).thenReturn(List.of(captured));

        List<Notice> notices = coverage.check(CLUSTER, ast("SELECT * FROM \"ORDER.IN\""), List.of(target("ORDER.IN")));

        assertThat(notices).anySatisfy(notice -> {
            assertThat(notice.kind()).isEqualTo(Notice.Kind.INDEX_COVERAGE_GAP);
            assertThat(notice.detail()).contains(began.toString()).contains("already on the queue");
        });
    }

    /**
     * A CAPTURE subscription is not capture until its tap is active on the node: one that is
     * pending or refused copies nothing, so it does not cover the queue there.
     */
    @Test
    void aCaptureSubscriptionCoversATargetOnlyWhereItsTapIsActive() {
        MessageIndexSubscriptionEntity sampled = subscription("ORDER.IN", Instant.now(), 7);
        sampled.setMode(CaptureMode.SAMPLE);
        MessageIndexSubscriptionEntity captured = subscription("PAY.IN", Instant.now(), 7);
        captured.setMode(CaptureMode.CAPTURE);
        when(subscriptions.findByClusterId(CLUSTER)).thenReturn(List.of(sampled, captured));
        Target pay = target("PAY.IN");

        when(captureNodes.findBySubscriptionIdAndNodeId(captured.getId(), pay.nodeId()))
                .thenReturn(Optional.of(node(captured, pay, CaptureState.PENDING)));
        assertThat(coverage.isCaptured(CLUSTER, List.of(pay))).isFalse();

        when(captureNodes.findBySubscriptionIdAndNodeId(captured.getId(), pay.nodeId()))
                .thenReturn(Optional.of(node(captured, pay, CaptureState.ACTIVE)));
        assertThat(coverage.isCaptured(CLUSTER, List.of(pay))).isTrue();
        assertThat(coverage.isCaptured(CLUSTER, List.of(target("ORDER.IN")))).isFalse();
    }

    private static MessageCaptureNodeEntity node(
            MessageIndexSubscriptionEntity subscription, Target target, CaptureState state) {
        MessageCaptureNodeEntity node = new MessageCaptureNodeEntity();
        node.setSubscriptionId(subscription.getId());
        node.setNodeId(target.nodeId());
        node.setCaptureState(state);
        return node;
    }
}
