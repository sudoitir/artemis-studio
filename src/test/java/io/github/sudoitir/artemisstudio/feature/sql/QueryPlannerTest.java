package io.github.sudoitir.artemisstudio.feature.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.feature.sql.QueryAst.Source;
import io.github.sudoitir.artemisstudio.feature.sql.QueryPlan.Notice;
import io.github.sudoitir.artemisstudio.feature.sql.QueryPlan.Target;
import io.github.sudoitir.artemisstudio.platform.broker.ClockOffsetRegistry.ClockOffset;
import io.github.sudoitir.artemisstudio.platform.broker.ClockOffsetService;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterDirectory;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.platform.scrape.QueueLocator;
import io.github.sudoitir.artemisstudio.platform.scrape.QueueLocator.QueueLocation;
import io.github.sudoitir.artemisstudio.platform.scrape.QueueSnapshot;
import io.github.sudoitir.artemisstudio.platform.scrape.QueueSnapshots;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The planner is where a query stops being text and becomes broker load, so the
 * cases that matter are the ones that decide how much load: which targets, which
 * backend, and how expensive the plan says it will be.
 */
class QueryPlannerTest {

    private static final UUID CLUSTER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-07T12:00:00Z");

    private final SqlQueryParser parser = new SqlQueryParser();
    private final SelectorRenderer renderer = new SelectorRenderer();
    private final PredicateSplitter splitter = new PredicateSplitter(renderer);

    private QueueSnapshots snapshots;
    private ClusterDirectory nodes;
    private ClockOffsetService clocks;
    private MessageIndexCoverage coverage;
    private QueueLocator locator;
    private QueryPlanner planner;

    @BeforeEach
    void setUp() {
        snapshots = mock(QueueSnapshots.class);
        nodes = mock(ClusterDirectory.class);
        clocks = mock(ClockOffsetService.class);
        coverage = mock(MessageIndexCoverage.class);
        locator = mock(QueueLocator.class);
        when(clocks.offsetFor(any())).thenReturn(Optional.of(new ClockOffset(0, 5, 10, 3, NOW)));
        when(coverage.isCaptured(any(), any())).thenReturn(false);
        when(coverage.check(any(), any(), any())).thenReturn(List.of());
        planner = newPlanner(defaults());
    }

    private QueryPlanner newPlanner(SqlProperties sql) {
        SqlProperties properties = sql;
        return new QueryPlanner(
                snapshots,
                locator,
                nodes,
                splitter,
                renderer,
                clocks,
                properties,
                coverage,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private SqlProperties defaults() {
        return new SqlProperties(
                50, 50_000L, 2_000, 250_000L, Duration.ofSeconds(30), 2, Duration.ofSeconds(5), Duration.ofSeconds(1));
    }

    private QueryPlan plan(String sql) {
        return planner.plan(CLUSTER, parser.parse(sql));
    }

    // ---- fixtures -------------------------------------------------------

    /** A node whose Artemis node id decides which endpoints are one logical node. */
    private BrokerNodeEntity node(String name, String artemisNodeId) {
        BrokerNodeEntity entity = instantiate(BrokerNodeEntity.class);
        set(entity, "id", UUID.randomUUID());
        set(entity, "name", name);
        set(entity, "artemisNodeId", artemisNodeId);
        set(entity, "clusterId", CLUSTER);
        return entity;
    }

    private QueueSnapshot snapshot(BrokerNodeEntity node, String queue, long depth) {
        return new QueueSnapshot(
                CLUSTER, node.getId(), queue, queue, "ANYCAST", false, false, null, depth, 0, 0, 0, 0, 0, 0);
    }

    /** Both entities keep a protected no-arg constructor for JPA; tests use the same one. */
    private static <T> T instantiate(Class<T> type) {
        try {
            var constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("fixture could not build " + type.getSimpleName(), e);
        }
    }

    private static void set(Object target, String field, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("fixture could not set " + field, e);
        }
    }

    private void given(List<BrokerNodeEntity> nodeList, List<QueueSnapshot> snapshotList) {
        when(nodes.nodes(CLUSTER)).thenReturn(new ArrayList<>(nodeList));
        when(snapshots.forCluster(CLUSTER)).thenReturn(new ArrayList<>(snapshotList));
    }

    // ---- targets --------------------------------------------------------

    @Test
    void aWildcardResolvesToEveryMatchingQueue() {
        BrokerNodeEntity a = node("broker-1", "node-a");
        given(
                List.of(a),
                List.of(snapshot(a, "ORDER.IN", 10), snapshot(a, "ORDER.OUT", 20), snapshot(a, "AUDIT.IN", 5)));

        QueryPlan plan = plan("SELECT * FROM \"ORDER.*\"");

        assertThat(plan.targets()).extracting(Target::queueName).containsExactly("ORDER.IN", "ORDER.OUT");
    }

    @Test
    void anArtemisHashWildcardSpansLevels() {
        BrokerNodeEntity a = node("broker-1", "node-a");
        given(List.of(a), List.of(snapshot(a, "ORDER.EU.IN", 1), snapshot(a, "ORDER.IN", 1)));

        assertThat(plan("SELECT * FROM \"ORDER.#\"").targets()).hasSize(2);
        // '*' is one level, so it does not reach ORDER.EU.IN.
        assertThat(plan("SELECT * FROM \"ORDER.*\"").targets())
                .extracting(Target::queueName)
                .containsExactly("ORDER.IN");
    }

    @Test
    void aPairedPrimaryAndBackupCountOnce() {
        // Both endpoints carry the same Artemis node id, so they are one logical node.
        // Without de-duplication every matching message would be returned twice.
        BrokerNodeEntity primary = node("broker-1", "node-a");
        BrokerNodeEntity backup = node("broker-1-backup", "node-a");
        given(List.of(primary, backup), List.of(snapshot(primary, "ORDER.IN", 10), snapshot(backup, "ORDER.IN", 10)));

        assertThat(plan("SELECT * FROM \"ORDER.IN\"").targets()).hasSize(1);
    }

    @Test
    void aPatternMatchingNothingSaysSoRatherThanReturningAnEmptyResult() {
        BrokerNodeEntity a = node("broker-1", "node-a");
        given(List.of(a), List.of(snapshot(a, "ORDER.IN", 10)));

        QueryPlan plan = plan("SELECT * FROM \"NOTHING.*\"");

        assertThat(plan.targets()).isEmpty();
        assertThat(plan.notices()).extracting(Notice::kind).contains(Notice.Kind.NO_QUEUE_MATCHED);
    }

    /**
     * A queue created a moment ago is on the broker before the scrape reaches it. Named
     * outright, it is looked up live, rather than reported as a queue that does not exist.
     */
    @Test
    void aNamedQueueTheScrapeHasNotReachedIsLookedUpLive() {
        BrokerNodeEntity a = node("broker-1", "node-a");
        given(List.of(a), List.of(snapshot(a, "ORDER.IN", 10)));
        when(locator.locate(CLUSTER, "FRESH"))
                .thenReturn(List.of(new QueueLocation(a.getId(), "FRESH", "FRESH", "ANYCAST", 3)));

        QueryPlan plan = plan("SELECT * FROM \"FRESH\"");

        assertThat(plan.targets()).singleElement().satisfies(t -> {
            assertThat(t.queueName()).isEqualTo("FRESH");
            assertThat(t.nodeId()).isEqualTo(a.getId());
            assertThat(t.messageCount()).isEqualTo(3);
        });
        assertThat(plan.notices()).extracting(Notice::kind).doesNotContain(Notice.Kind.NO_QUEUE_MATCHED);
    }

    @Test
    void aNodePredicateFiltersTargetsSoTheOtherNodeIsNeverAsked() {
        BrokerNodeEntity a = node("broker-1", "node-a");
        BrokerNodeEntity b = node("broker-2", "node-b");
        given(List.of(a, b), List.of(snapshot(a, "ORDER.IN", 10), snapshot(b, "ORDER.IN", 10)));

        QueryPlan plan = plan("SELECT * FROM \"ORDER.IN\" WHERE node = 'broker-2'");

        assertThat(plan.targets()).extracting(Target::nodeName).containsExactly("broker-2");
    }

    @Test
    void theTargetCapIsApplied() {
        BrokerNodeEntity a = node("broker-1", "node-a");
        List<QueueSnapshot> many = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            many.add(snapshot(a, "Q." + i, 1));
        }
        given(List.of(a), many);
        planner = newPlanner(new SqlProperties(
                3, 50_000L, 2_000, 250_000L, Duration.ofSeconds(30), 2, Duration.ofSeconds(5), Duration.ofSeconds(1)));

        assertThat(plan("SELECT * FROM \"Q.#\"").targets()).hasSize(3);
    }

    // ---- source ---------------------------------------------------------

    /**
     * The plain query reads the index only when every target is captured on the node it sits
     * on (ADR-0086). A CAPTURE subscription whose tap is not active on a node is not capture.
     */
    @Test
    void anUnqualifiedQueryPrefersTheIndexOnlyWhenEveryTargetIsCapturedOnItsNode() {
        BrokerNodeEntity a = node("broker-1", "node-a");
        given(List.of(a), List.of(snapshot(a, "ORDER.IN", 10), snapshot(a, "ORDER.OUT", 10)));

        when(coverage.isCaptured(eq(CLUSTER), any())).thenReturn(false);
        assertThat(plan("SELECT * FROM \"ORDER.*\"").resolvedSource()).isEqualTo(Source.BROKER);

        when(coverage.isCaptured(eq(CLUSTER), any())).thenReturn(true);
        assertThat(plan("SELECT * FROM \"ORDER.*\"").resolvedSource()).isEqualTo(Source.INDEX);
    }

    @Test
    void theQualifierOverridesTheDefault() {
        BrokerNodeEntity a = node("broker-1", "node-a");
        given(List.of(a), List.of(snapshot(a, "ORDER.IN", 10)));
        when(coverage.isCaptured(any(), any())).thenReturn(true);

        assertThat(plan("SELECT * FROM broker.\"ORDER.IN\"").resolvedSource()).isEqualTo(Source.BROKER);
    }

    @Test
    void anIndexOnlyColumnAgainstALiveBrokerIsRefusedRatherThanReturningNothing() {
        BrokerNodeEntity a = node("broker-1", "node-a");
        given(List.of(a), List.of(snapshot(a, "ORDER.IN", 10)));

        assertThatThrownBy(() -> plan("SELECT * FROM broker.\"ORDER.IN\" WHERE observedAt > now() - interval '1 day'"))
                .isInstanceOf(SqlSyntaxException.class)
                .hasMessageContaining("no meaning against a live broker");
    }

    // ---- cost -----------------------------------------------------------

    @Test
    void aPushdownOnlyQueryIsEstimatedAtTheLimitNotTheQueueDepth() {
        BrokerNodeEntity a = node("broker-1", "node-a");
        given(List.of(a), List.of(snapshot(a, "ORDER.IN", 1_000_000)));

        QueryPlan plan = plan("SELECT * FROM \"ORDER.IN\" WHERE priority > 4 LIMIT 100");

        assertThat(plan.requiresScan()).isFalse();
        assertThat(plan.estimatedMessagesExamined()).isEqualTo(100);
        assertThat(plan.pushedDown()).containsExactly("priority > 4");
        assertThat(plan.scanned()).isEmpty();
    }

    @Test
    void aBodyPredicateIsEstimatedAtTheWholeQueueDepth() {
        BrokerNodeEntity a = node("broker-1", "node-a");
        given(List.of(a), List.of(snapshot(a, "ORDER.IN", 12_000)));

        QueryPlan plan = plan("SELECT * FROM \"ORDER.IN\" WHERE body LIKE '%timeout%' LIMIT 100");

        assertThat(plan.requiresScan()).isTrue();
        assertThat(plan.estimatedMessagesExamined()).isEqualTo(12_000);
        assertThat(plan.scanned()).containsExactly("body LIKE '%timeout%'");
    }

    @Test
    void anOverBudgetQueryIsRefusedWithTheEstimateAndAHint() {
        BrokerNodeEntity a = node("broker-1", "node-a");
        given(List.of(a), List.of(snapshot(a, "ORDER.IN", 1_000_000)));

        QueryPlan plan = plan("SELECT * FROM \"ORDER.IN\" WHERE body LIKE '%x%'");

        assertThatThrownBy(() -> planner.enforceCostCeiling(plan))
                .isInstanceOf(CostRefusedException.class)
                .hasMessageContaining("250000")
                .hasMessageContaining("narrower time window");
    }

    @Test
    void aWithinBudgetQueryIsNotRefused() {
        BrokerNodeEntity a = node("broker-1", "node-a");
        given(List.of(a), List.of(snapshot(a, "ORDER.IN", 500)));

        planner.enforceCostCeiling(plan("SELECT * FROM \"ORDER.IN\" WHERE body LIKE '%x%'"));
    }

    // ---- clock ----------------------------------------------------------

    @Test
    void aRelativeWindowIsRenderedInTheNodesOwnTime() {
        BrokerNodeEntity a = node("broker-1", "node-a");
        given(List.of(a), List.of(snapshot(a, "ORDER.IN", 10)));
        // The broker is 60s ahead of Studio, so its "an hour ago" is 60s later too.
        when(clocks.offsetFor(a.getId())).thenReturn(Optional.of(new ClockOffset(60_000, 5, 10, 3, NOW)));

        QueryPlan plan = plan("SELECT * FROM \"ORDER.IN\" WHERE timestamp > now() - interval '1 hour'");

        long expected = NOW.plusSeconds(60).minus(Duration.ofHours(1)).toEpochMilli();
        assertThat(plan.selector()).isEqualTo("AMQTimestamp > " + expected);
    }

    @Test
    void anUnmeasuredClockIsDisclosedRatherThanAssumedZero() {
        BrokerNodeEntity a = node("broker-1", "node-a");
        given(List.of(a), List.of(snapshot(a, "ORDER.IN", 10)));
        when(clocks.offsetFor(a.getId())).thenReturn(Optional.empty());

        QueryPlan plan = plan("SELECT * FROM \"ORDER.IN\" WHERE timestamp > now() - interval '1 hour'");

        assertThat(plan.notices()).extracting(Notice::kind).contains(Notice.Kind.CLOCK_OFFSET_UNKNOWN);
    }

    @Test
    void aQueryWithNoRelativeWindowDoesNotWarnAboutClocks() {
        BrokerNodeEntity a = node("broker-1", "node-a");
        given(List.of(a), List.of(snapshot(a, "ORDER.IN", 10)));
        when(clocks.offsetFor(a.getId())).thenReturn(Optional.empty());

        assertThat(plan("SELECT * FROM \"ORDER.IN\" WHERE priority > 4").notices())
                .extracting(Notice::kind)
                .doesNotContain(Notice.Kind.CLOCK_OFFSET_UNKNOWN);
    }
}
