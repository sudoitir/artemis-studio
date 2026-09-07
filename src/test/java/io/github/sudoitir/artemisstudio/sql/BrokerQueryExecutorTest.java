package io.github.sudoitir.artemisstudio.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.broker.BrokerConnectionException;
import io.github.sudoitir.artemisstudio.broker.ClockOffsetRegistry.ClockOffset;
import io.github.sudoitir.artemisstudio.broker.MessageBrowser.BodyEncoding;
import io.github.sudoitir.artemisstudio.broker.MessageBrowser.BrowsePage;
import io.github.sudoitir.artemisstudio.broker.MessageBrowser.BrowsedMessage;
import io.github.sudoitir.artemisstudio.broker.MessageTransport;
import io.github.sudoitir.artemisstudio.broker.MessageTransport.BrowseResult;
import io.github.sudoitir.artemisstudio.broker.MessageTransport.Channel;
import io.github.sudoitir.artemisstudio.broker.MessageTransport.SendSpec;
import io.github.sudoitir.artemisstudio.broker.MessageTransport.TransportTarget;
import io.github.sudoitir.artemisstudio.config.ArtemisStudioProperties;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeRepository;
import io.github.sudoitir.artemisstudio.persist.QueueSnapshotEntity;
import io.github.sudoitir.artemisstudio.persist.QueueSnapshotRepository;
import io.github.sudoitir.artemisstudio.scheduler.NodeCallLimiter;
import io.github.sudoitir.artemisstudio.service.ClockOffsetService;
import io.github.sudoitir.artemisstudio.sql.QueryPlan.Notice;
import io.github.sudoitir.artemisstudio.sql.QueryResult.Bound;
import io.github.sudoitir.artemisstudio.sql.QueryResult.NodeOutcome;
import io.github.sudoitir.artemisstudio.sql.QueryResult.Row;
import io.github.sudoitir.artemisstudio.support.Props;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The executor's job is to stay inside its bounds and to be honest about the ones it
 * hits. A bounded result that does not say it is bounded is the failure that matters,
 * so most of these assert the report rather than the rows.
 */
class BrokerQueryExecutorTest {

    private static final UUID CLUSTER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-07T12:00:00Z");

    private final SqlQueryParser parser = new SqlQueryParser();
    private final SelectorRenderer renderer = new SelectorRenderer();
    private final PredicateSplitter splitter = new PredicateSplitter(renderer);
    private final MessagePredicate residuals = new MessagePredicate();

    private QueueSnapshotRepository snapshots;
    private BrokerNodeRepository nodes;
    private ClockOffsetService clocks;
    private MessageIndexCoverage coverage;
    private NodeCallLimiter limiter;
    private BrokerNodeEntity node;

    @BeforeEach
    void setUp() {
        snapshots = mock(QueueSnapshotRepository.class);
        nodes = mock(BrokerNodeRepository.class);
        clocks = mock(ClockOffsetService.class);
        coverage = mock(MessageIndexCoverage.class);
        limiter = mock(NodeCallLimiter.class);
        when(clocks.offsetFor(any())).thenReturn(Optional.of(new ClockOffset(0, 5, 10, 3, NOW)));
        when(coverage.isIndexed(any(), any())).thenReturn(false);
        when(coverage.check(any(), any(), any())).thenReturn(List.of());
        node = node("broker-1", "node-a");
    }

    // ---- harness --------------------------------------------------------

    private ArtemisStudioProperties props(long scanCap, int maxRows, Duration timeout) {
        return Props.sql(new ArtemisStudioProperties.Sql(
                50, scanCap, maxRows, Long.MAX_VALUE, timeout, 2, Duration.ofSeconds(5), Duration.ofSeconds(1)));
    }

    private QueryPlanner planner(ArtemisStudioProperties properties) {
        return new QueryPlanner(
                snapshots, nodes, splitter, renderer, clocks, properties, coverage, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private QueryResult run(String sql, MessageTransport transport, ArtemisStudioProperties properties) {
        return run(sql, transport, properties, new CountingSink());
    }

    private QueryResult run(
            String sql, MessageTransport transport, ArtemisStudioProperties properties, BrokerQueryExecutor.Sink sink) {
        QueryPlanner planner = planner(properties);
        BrokerQueryExecutor executor = new BrokerQueryExecutor(nodes, limiter, residuals, planner, properties);
        QueryPlan plan = planner.plan(CLUSTER, parser.parse(sql));
        return executor.execute(CLUSTER, plan, transport, sink);
    }

    static class CountingSink implements BrokerQueryExecutor.Sink {
        final List<Row> rows = new ArrayList<>();
        final List<NodeOutcome> outcomes = new ArrayList<>();
        volatile boolean cancelled;

        @Override
        public synchronized void row(Row row) {
            rows.add(row);
        }

        @Override
        public synchronized void nodeFinished(NodeOutcome outcome) {
            outcomes.add(outcome);
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

    /** A transport that serves a fixed queue depth, counting the browses it is asked for. */
    private MessageTransport transportServing(int totalMessages, boolean truncated) {
        return new MessageTransport() {
            final AtomicInteger calls = new AtomicInteger();

            @Override
            public Channel channel() {
                return Channel.JOLOKIA;
            }

            @Override
            public BrowseResult browse(TransportTarget target, int page, int size, String filter) {
                calls.incrementAndGet();
                int from = (page - 1) * size;
                List<BrowsedMessage> messages = new ArrayList<>();
                for (int i = from; i < Math.min(from + size, totalMessages); i++) {
                    messages.add(message(i, truncated));
                }
                return new BrowseResult(new BrowsePage(messages, totalMessages), Channel.JOLOKIA);
            }

            @Override
            public void send(TransportTarget target, SendSpec spec) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static BrowsedMessage message(int i, boolean truncated) {
        return new BrowsedMessage(
                i,
                3,
                true,
                4,
                NOW.toEpochMilli(),
                0,
                100,
                null,
                null,
                null,
                null,
                "body-" + i + "-needle",
                BodyEncoding.TEXT,
                null,
                truncated,
                truncated ? 256 : null,
                Map.of("tenant", "acme"),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of());
    }

    private BrokerNodeEntity node(String name, String artemisNodeId) {
        BrokerNodeEntity entity = instantiate(BrokerNodeEntity.class);
        set(entity, "id", UUID.randomUUID());
        set(entity, "name", name);
        set(entity, "artemisNodeId", artemisNodeId);
        set(entity, "clusterId", CLUSTER);
        set(entity, "jolokiaUrl", "http://broker:8161/console/jolokia");
        return entity;
    }

    private QueueSnapshotEntity snapshot(BrokerNodeEntity on, String queue, long depth) {
        QueueSnapshotEntity entity = instantiate(QueueSnapshotEntity.class);
        set(entity, "nodeId", on.getId());
        set(entity, "clusterId", CLUSTER);
        set(entity, "queueName", queue);
        set(entity, "address", queue);
        set(entity, "routingType", "ANYCAST");
        set(entity, "messageCount", depth);
        return entity;
    }

    private void given(List<BrokerNodeEntity> nodeList, List<QueueSnapshotEntity> snapshotList) {
        when(nodes.findByClusterIdOrderByNameAsc(CLUSTER)).thenReturn(new ArrayList<>(nodeList));
        when(snapshots.findByClusterId(CLUSTER)).thenReturn(new ArrayList<>(snapshotList));
    }

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

    // ---- tests ----------------------------------------------------------

    @Test
    void aMatchingQueryReturnsRowsAttributedToTheirNodeAndQueue() {
        given(List.of(node), List.of(snapshot(node, "ORDER.IN", 5)));

        QueryResult result = run(
                "SELECT * FROM \"ORDER.IN\" WHERE body LIKE '%needle%'",
                transportServing(5, false), props(50_000, 2_000, Duration.ofSeconds(30)));

        assertThat(result.rows()).hasSize(5);
        assertThat(result.rows()).allSatisfy(row -> {
            assertThat(row.nodeName()).isEqualTo("broker-1");
            assertThat(row.queueName()).isEqualTo("ORDER.IN");
            assertThat(row.source()).isEqualTo(QueryAst.Source.BROKER);
        });
        assertThat(result.isPartial()).isFalse();
    }

    @Test
    void aResidualPredicateThatMatchesNothingReturnsNoRowsButStillAnswers() {
        given(List.of(node), List.of(snapshot(node, "ORDER.IN", 5)));

        QueryResult result = run(
                "SELECT * FROM \"ORDER.IN\" WHERE body LIKE '%haystack%'",
                transportServing(5, false), props(50_000, 2_000, Duration.ofSeconds(30)));

        assertThat(result.rows()).isEmpty();
        assertThat(result.nodes()).extracting(NodeOutcome::status).containsExactly(NodeOutcome.Status.ANSWERED);
        assertThat(result.nodes().getFirst().examined()).isEqualTo(5);
    }

    @Test
    void theScanCapStopsTheQueryAndTheResultSaysSo() {
        given(List.of(node), List.of(snapshot(node, "ORDER.IN", 1_000)));

        QueryResult result = run(
                "SELECT * FROM \"ORDER.IN\" WHERE body LIKE '%haystack%'",
                transportServing(1_000, false), props(200, 2_000, Duration.ofSeconds(30)));

        assertThat(result.boundsReached()).extracting(Bound::kind).contains(Bound.Kind.SCAN_CAP);
        assertThat(result.isPartial()).isTrue();
    }

    @Test
    void theRowLimitIsExactRatherThanOvershotByAPage() {
        given(List.of(node), List.of(snapshot(node, "ORDER.IN", 1_000)));

        QueryResult result = run(
                "SELECT * FROM \"ORDER.IN\" WHERE body LIKE '%needle%'",
                transportServing(1_000, false), props(50_000, 10, Duration.ofSeconds(30)));

        assertThat(result.rows()).hasSize(10);
        assertThat(result.boundsReached()).extracting(Bound::kind).contains(Bound.Kind.ROW_LIMIT);
    }

    @Test
    void aTimeoutIsReportedRatherThanReturningAQuietShortResult() {
        given(List.of(node), List.of(snapshot(node, "ORDER.IN", 1_000)));

        QueryResult result = run(
                "SELECT * FROM \"ORDER.IN\" WHERE body LIKE '%needle%'",
                transportServing(1_000, false), props(50_000, 2_000, Duration.ZERO));

        assertThat(result.boundsReached()).extracting(Bound::kind).contains(Bound.Kind.TIMEOUT);
        assertThat(result.isPartial()).isTrue();
    }

    @Test
    void aFailedNodeIsReportedWithItsReasonAndTheOthersStillAnswer() {
        BrokerNodeEntity healthy = node;
        BrokerNodeEntity broken = node("broker-2", "node-b");
        given(List.of(healthy, broken), List.of(snapshot(healthy, "ORDER.IN", 3), snapshot(broken, "ORDER.OUT", 3)));

        MessageTransport flaky = new MessageTransport() {
            @Override
            public Channel channel() {
                return Channel.JOLOKIA;
            }

            @Override
            public BrowseResult browse(TransportTarget target, int page, int size, String filter) {
                if ("ORDER.OUT".equals(target.queueName())) {
                    throw new BrokerConnectionException(
                            BrokerConnectionException.Kind.UNREACHABLE, "connection refused");
                }
                List<BrowsedMessage> messages = new ArrayList<>();
                for (int i = 0; i < 3; i++) {
                    messages.add(message(i, false));
                }
                return new BrowseResult(new BrowsePage(messages, 3), Channel.JOLOKIA);
            }

            @Override
            public void send(TransportTarget target, SendSpec spec) {
                throw new UnsupportedOperationException();
            }
        };

        QueryResult result = run("SELECT * FROM \"ORDER.#\"", flaky, props(50_000, 2_000, Duration.ofSeconds(30)));

        assertThat(result.rows()).hasSize(3);
        assertThat(result.isPartial()).isTrue();
        NodeOutcome failed = result.nodes().stream()
                .filter(n -> n.status() == NodeOutcome.Status.FAILED)
                .findFirst()
                .orElseThrow();
        assertThat(failed.nodeName()).isEqualTo("broker-2");
        assertThat(failed.detail()).contains("connection refused");
    }

    @Test
    void abandoningTheQueryStopsFurtherBrokerReads() {
        given(List.of(node), List.of(snapshot(node, "ORDER.IN", 1_000)));
        AtomicInteger browses = new AtomicInteger();
        MessageTransport counting = new MessageTransport() {
            @Override
            public Channel channel() {
                return Channel.JOLOKIA;
            }

            @Override
            public BrowseResult browse(TransportTarget target, int page, int size, String filter) {
                browses.incrementAndGet();
                List<BrowsedMessage> messages = new ArrayList<>();
                for (int i = 0; i < size; i++) {
                    messages.add(message(i, false));
                }
                return new BrowseResult(new BrowsePage(messages, 1_000), Channel.JOLOKIA);
            }

            @Override
            public void send(TransportTarget target, SendSpec spec) {
                throw new UnsupportedOperationException();
            }
        };
        CountingSink sink = new CountingSink();
        sink.cancelled = true;

        QueryResult result =
                run("SELECT * FROM \"ORDER.IN\"", counting, props(50_000, 2_000, Duration.ofSeconds(30)), sink);

        assertThat(browses.get()).isZero();
        assertThat(result.rows()).isEmpty();
    }

    @Test
    void aTruncatedBodyUnderABodyPredicateMakesTheResultSayItMayBeIncomplete() {
        given(List.of(node), List.of(snapshot(node, "ORDER.IN", 3)));

        QueryResult result = run(
                "SELECT * FROM \"ORDER.IN\" WHERE body LIKE '%needle%'",
                transportServing(3, true), props(50_000, 2_000, Duration.ofSeconds(30)));

        assertThat(result.notices()).extracting(Notice::kind).contains(Notice.Kind.BODY_TRUNCATED);
        assertThat(result.notices())
                .anySatisfy(n -> assertThat(n.detail()).contains("management-message-attribute-size-limit"));
    }

    @Test
    void aTruncatedBodyWithNoBodyPredicateDoesNotWarn() {
        given(List.of(node), List.of(snapshot(node, "ORDER.IN", 3)));

        QueryResult result = run(
                "SELECT * FROM \"ORDER.IN\" WHERE priority > 1",
                transportServing(3, true),
                props(50_000, 2_000, Duration.ofSeconds(30)));

        assertThat(result.notices()).extracting(Notice::kind).doesNotContain(Notice.Kind.BODY_TRUNCATED);
    }

    @Test
    void everyBrokerReadTakesARateLimitPermit() throws Exception {
        given(List.of(node), List.of(snapshot(node, "ORDER.IN", 3)));

        run("SELECT * FROM \"ORDER.IN\"", transportServing(3, false), props(50_000, 2_000, Duration.ofSeconds(30)));

        org.mockito.Mockito.verify(limiter, org.mockito.Mockito.atLeastOnce()).acquire(node.getId());
    }
}
