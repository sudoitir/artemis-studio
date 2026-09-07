package io.github.sudoitir.artemisstudio.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import io.github.sudoitir.artemisstudio.sql.QueryResult.NodeOutcome;
import io.github.sudoitir.artemisstudio.sql.QueryResult.Row;
import io.github.sudoitir.artemisstudio.support.Props;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The tail is a sample, and the two things that make it a usable one are that it
 * does not re-deliver what it has already shown and that it stops when its client
 * does. Both are asserted here; the honesty of the gap figure is the third.
 */
class SqlTailPollerTest {

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
    private final AtomicLong messagesAdded = new AtomicLong(100);

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
        node = node();
        when(nodes.findByClusterIdOrderByNameAsc(CLUSTER)).thenReturn(List.of(node));
        // Re-read on every call, so a test can move the counter between polls.
        when(snapshots.findByClusterId(CLUSTER))
                .thenAnswer(invocation -> List.of(snapshot("ORDER.IN", 5, messagesAdded.get())));
    }

    // ---- tests ----------------------------------------------------------

    @Test
    void thePollPushesTheHighWaterMarkIntoTheSelectorRatherThanRereadingTheQueue() {
        RecordingTransport transport = new RecordingTransport(3);
        CollectingListener listener = new CollectingListener();
        SqlTailPoller poller = poller();
        poller.start(CLUSTER, plan("SELECT * FROM \"ORDER.IN\""), transport, listener);

        poller.tick();
        await(() -> !listener.statuses.isEmpty());

        assertThat(transport.filters).isNotEmpty();
        assertThat(transport.filters.getFirst()).contains("JMSTimestamp >= " + NOW.toEpochMilli());
    }

    @Test
    void aMessageAlreadyShownIsNotShownAgainOnTheNextPoll() {
        RecordingTransport transport = new RecordingTransport(3);
        CollectingListener listener = new CollectingListener();
        SqlTailPoller poller = poller();
        poller.start(CLUSTER, plan("SELECT * FROM \"ORDER.IN\""), transport, listener);

        poller.tick();
        await(() -> listener.statuses.size() == 1);
        assertThat(listener.rows).hasSize(3);

        // The broker still returns them: the mark is a `>=`, so the messages stamped
        // in the same millisecond come back and are filtered by id here.
        poller.tick();
        await(() -> listener.statuses.size() == 2);
        assertThat(listener.rows).hasSize(3);
        assertThat(listener.statuses.getLast().shown()).isEqualTo(3);
    }

    @Test
    void aClientThatHasGoneAwayStopsTheTailWithoutAnotherBrokerRead() {
        RecordingTransport transport = new RecordingTransport(3);
        CollectingListener listener = new CollectingListener();
        SqlTailPoller poller = poller();
        poller.start(CLUSTER, plan("SELECT * FROM \"ORDER.IN\""), transport, listener);

        listener.cancelled = true;
        poller.tick();

        assertThat(poller.activeTails()).isZero();
        assertThat(transport.filters).isEmpty();
    }

    @Test
    void stoppingATailRemovesItEvenWhileItsListenerIsStillAlive() {
        RecordingTransport transport = new RecordingTransport(3);
        CollectingListener listener = new CollectingListener();
        SqlTailPoller poller = poller();
        SqlTailPoller.Tail tail = poller.start(CLUSTER, plan("SELECT * FROM \"ORDER.IN\""), transport, listener);

        tail.stop();
        poller.tick();

        assertThat(poller.activeTails()).isZero();
        assertThat(transport.filters).isEmpty();
    }

    @Test
    void theObservedGapIsReportedFromTheMessagesAddedDeltaRatherThanImplied() {
        RecordingTransport transport = new RecordingTransport(3);
        CollectingListener listener = new CollectingListener();
        SqlTailPoller poller = poller();
        poller.start(CLUSTER, plan("SELECT * FROM \"ORDER.IN\""), transport, listener);

        // Thirty arrived; the poll saw three of them still on the queue. The rest
        // were consumed between reads and are exactly what a sample cannot show.
        messagesAdded.set(130);
        poller.tick();
        await(() -> !listener.statuses.isEmpty());

        SqlTailPoller.TailStatus status = listener.statuses.getLast();
        assertThat(status.enqueued()).isEqualTo(30);
        assertThat(status.shown()).isEqualTo(3);
        // No predicate, so the subtraction is a fact rather than an upper bound.
        assertThat(status.everyMessageMatches()).isTrue();
    }

    @Test
    void aQueryWithAPredicateSaysItsGapFigureCannotBeSeparatedFromNonMatches() {
        CollectingListener listener = new CollectingListener();
        SqlTailPoller poller = poller();
        poller.start(
                CLUSTER, plan("SELECT * FROM \"ORDER.IN\" WHERE priority > 4"), new RecordingTransport(0), listener);

        poller.tick();
        await(() -> !listener.statuses.isEmpty());

        assertThat(listener.statuses.getLast().everyMessageMatches()).isFalse();
    }

    // ---- harness --------------------------------------------------------

    private SqlTailPoller poller() {
        ArtemisStudioProperties properties = Props.sql(new ArtemisStudioProperties.Sql(
                50,
                50_000,
                2_000,
                Long.MAX_VALUE,
                Duration.ofSeconds(30),
                2,
                Duration.ofSeconds(5),
                Duration.ofSeconds(1)));
        QueryPlanner planner = new QueryPlanner(
                snapshots, nodes, splitter, renderer, clocks, properties, coverage, Clock.fixed(NOW, ZoneOffset.UTC));
        return new SqlTailPoller(new BrokerQueryExecutor(nodes, limiter, residuals, planner, properties), snapshots);
    }

    private QueryPlan plan(String sql) {
        ArtemisStudioProperties properties = Props.sql(new ArtemisStudioProperties.Sql(
                50,
                50_000,
                2_000,
                Long.MAX_VALUE,
                Duration.ofSeconds(30),
                2,
                Duration.ofSeconds(5),
                Duration.ofSeconds(1)));
        QueryPlanner planner = new QueryPlanner(
                snapshots, nodes, splitter, renderer, clocks, properties, coverage, Clock.fixed(NOW, ZoneOffset.UTC));
        return planner.plan(CLUSTER, parser.parse(sql));
    }

    /** A poll runs on its own thread, so the assertions wait for it rather than assume it. */
    private static void await(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for the poll", e);
            }
        }
        throw new AssertionError("the poll did not produce what the test waited for");
    }

    private static final class CollectingListener implements SqlTailPoller.Listener {
        final List<Row> rows = new CopyOnWriteArrayList<>();
        final List<NodeOutcome> outcomes = new CopyOnWriteArrayList<>();
        final List<SqlTailPoller.TailStatus> statuses = new CopyOnWriteArrayList<>();
        volatile boolean cancelled;

        @Override
        public void row(Row row) {
            rows.add(row);
        }

        @Override
        public void nodeFinished(NodeOutcome outcome) {
            outcomes.add(outcome);
        }

        @Override
        public void status(SqlTailPoller.TailStatus status) {
            statuses.add(status);
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

    /** Serves a fixed set of messages and records the selector it was asked to apply. */
    private static final class RecordingTransport implements MessageTransport {
        private final int depth;
        final List<String> filters = Collections.synchronizedList(new ArrayList<>());

        private RecordingTransport(int depth) {
            this.depth = depth;
        }

        @Override
        public Channel channel() {
            return Channel.JOLOKIA;
        }

        @Override
        public BrowseResult browse(TransportTarget target, int page, int size, String filter) {
            filters.add(filter == null ? "" : filter);
            List<BrowsedMessage> messages = new ArrayList<>();
            if (page == 1) {
                for (int i = 0; i < depth; i++) {
                    messages.add(message(i));
                }
            }
            return new BrowseResult(new BrowsePage(messages, depth), Channel.JOLOKIA);
        }

        @Override
        public void send(TransportTarget target, SendSpec spec) {
            throw new UnsupportedOperationException();
        }
    }

    private static BrowsedMessage message(int i) {
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
                "body-" + i,
                BodyEncoding.TEXT,
                null,
                false,
                null,
                Map.of("tenant", "acme"),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of());
    }

    private BrokerNodeEntity node() {
        BrokerNodeEntity entity = instantiate(BrokerNodeEntity.class);
        set(entity, "id", UUID.randomUUID());
        set(entity, "name", "broker-1");
        set(entity, "artemisNodeId", "node-a");
        set(entity, "clusterId", CLUSTER);
        set(entity, "jolokiaUrl", "http://broker:8161/console/jolokia");
        return entity;
    }

    private QueueSnapshotEntity snapshot(String queue, long depth, long added) {
        QueueSnapshotEntity entity = instantiate(QueueSnapshotEntity.class);
        set(entity, "nodeId", node.getId());
        set(entity, "clusterId", CLUSTER);
        set(entity, "queueName", queue);
        set(entity, "address", queue);
        set(entity, "routingType", "ANYCAST");
        set(entity, "messageCount", depth);
        set(entity, "messagesAdded", added);
        return entity;
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
}
