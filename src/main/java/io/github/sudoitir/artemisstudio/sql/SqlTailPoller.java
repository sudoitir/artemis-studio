package io.github.sudoitir.artemisstudio.sql;

import io.github.sudoitir.artemisstudio.broker.MessageTransport;
import io.github.sudoitir.artemisstudio.persist.QueueSnapshotEntity;
import io.github.sudoitir.artemisstudio.persist.QueueSnapshotRepository;
import io.github.sudoitir.artemisstudio.sql.QueryPlan.Target;
import io.github.sudoitir.artemisstudio.sql.QueryResult.NodeOutcome;
import io.github.sudoitir.artemisstudio.sql.QueryResult.Row;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The live tail (ADR-0058 D7, ADR-0060). One mechanism: a plan is re-browsed at the
 * configured interval, from a per-target high-water mark on {@code (timestamp,
 * messageId)}, and whatever is new and still matches is emitted.
 *
 * <p>It is a <strong>sample, not a capture</strong>. Between two polls a message can
 * arrive and be consumed, and nothing here will ever see it. That is inherent to
 * polling and is the price of not mutating {@code broker.xml} and not consuming the
 * operator's messages. The consequence is stated permanently in the UI rather than
 * buried.
 *
 * <p>The mark is pushed into the JMS selector, so a poll costs the broker a filtered
 * page rather than a full re-read of the queue. Every read still takes the node's
 * management-call permit, and the plan's bounds still apply per poll.
 *
 * <p>A tail's plan is fixed for its lifetime: the targets were resolved against the
 * caller's cluster scope when the tail started, and re-planning on a scheduler thread
 * has no caller to check permissions against. A queue created after the tail started
 * is therefore not tailed until the query is re-run.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SqlTailPoller {

    private final BrokerQueryExecutor executor;
    private final QueueSnapshotRepository snapshots;

    /** Live tails. A tail is removed when its client goes away. */
    private final Set<Tail> tails = ConcurrentHashMap.newKeySet();

    /**
     * Polls run off the scheduler thread. A poll waits on rate-limit permits and can
     * take seconds; the shared scheduler also drives the SSE heartbeat and the alert
     * dispatcher, and neither may be held up behind a tail.
     */
    private final ExecutorService polls = Executors.newVirtualThreadPerTaskExecutor();

    /** Where a tail's output goes. The poller knows nothing about how it is delivered. */
    public interface Listener {
        void row(Row row);

        /**
         * Every row a poll returned, new or not — where {@link #row(Row)} is only what
         * has not been delivered before. The index capture writes from here, because a
         * message being seen again is exactly what it needs to record.
         */
        default void observed(Row row) {}

        void nodeFinished(NodeOutcome outcome);

        void status(TailStatus status);

        /** True once the client has gone away, which is what stops the tail. */
        boolean isCancelled();
    }

    /**
     * What the tail has seen, and what it can prove it missed.
     *
     * @param enqueued messages enqueued on the tailed queues since the tail started,
     *     from the {@code MessagesAdded} deltas in {@code queue_snapshot}
     * @param shown rows this tail has delivered
     * @param everyMessageMatches true when the query has no predicate at all, so every
     *     enqueued message would have matched and {@code enqueued - shown} is exactly
     *     the number that passed through unobserved. When false the difference also
     *     contains messages that simply did not match, and the two cannot be separated
     */
    public record TailStatus(long enqueued, long shown, long polls, Instant lastPollAt, boolean everyMessageMatches) {}

    /** A running tail. Held by the caller so it can stop it. */
    public final class Tail {
        private final UUID clusterId;
        private final QueryPlan plan;
        private final MessageTransport transport;
        private final Listener listener;
        private final boolean everyMessageMatches;

        /**
         * The soonest this tail may be polled again. A capture subscription sets its
         * own interval, so one slow-moving queue is not read at the console's cadence.
         */
        private final Duration minInterval;

        private volatile Instant nextDue = Instant.EPOCH;

        /** Per target: the newest timestamp seen, and the ids seen at exactly it. */
        private final Map<String, Mark> marks = new ConcurrentHashMap<>();

        /** Per target: {@code MessagesAdded} at the previous poll. */
        private final Map<String, Long> lastAdded = new ConcurrentHashMap<>();

        private final AtomicLong enqueued = new AtomicLong();
        private final AtomicLong shown = new AtomicLong();
        private final AtomicLong pollCount = new AtomicLong();
        private final AtomicBoolean polling = new AtomicBoolean();
        private volatile boolean stopped;

        private Tail(
                UUID clusterId,
                QueryPlan plan,
                MessageTransport transport,
                Listener listener,
                Duration minInterval,
                boolean fromBeginning) {
            this.clusterId = clusterId;
            this.plan = plan;
            this.transport = transport;
            this.listener = listener;
            this.minInterval = minInterval;
            this.everyMessageMatches =
                    plan.pushedDown().isEmpty() && plan.scanned().isEmpty();
            for (Target target : plan.targets()) {
                // A console tail starts at "now": the operator has just seen the static
                // result and wants what comes next. A capture starts at zero, so the
                // first poll indexes the backlog already on the queue — otherwise a
                // subscription created during an incident can answer nothing about the
                // messages the incident is about.
                marks.put(
                        key(target),
                        new Mark(fromBeginning ? 0 : target.brokerNow().toEpochMilli(), new HashSet<>()));
            }
            primeAdded();
        }

        public void stop() {
            stopped = true;
            tails.remove(this);
        }

        /**
         * Advance the mark for a row the static phase already delivered, so the first
         * poll does not send it a second time.
         */
        public void seen(Row row) {
            advance(key(row.nodeId(), row.queueName()), row);
        }

        private boolean cancelled() {
            return stopped || listener.isCancelled();
        }

        /** Only what arrived after the mark, evaluated by the broker rather than here. */
        private String selectorFor(Target target) {
            Mark mark = marks.get(key(target));
            // Greater-or-equal rather than greater: a message stamped in the same
            // millisecond as the mark would otherwise be skipped. The duplicates that
            // admits are filtered by id below, which is what the id half of the mark
            // is for.
            return mark == null ? null : "JMSTimestamp >= " + mark.timestamp();
        }

        private boolean isNew(Row row) {
            Mark mark = marks.get(key(row.nodeId(), row.queueName()));
            if (mark == null) {
                return true;
            }
            if (row.timestamp() > mark.timestamp()) {
                return true;
            }
            return row.timestamp() == mark.timestamp() && !mark.ids().contains(row.messageId());
        }

        private synchronized void advance(String key, Row row) {
            Mark mark = marks.get(key);
            if (mark == null || row.timestamp() > mark.timestamp()) {
                Set<Long> ids = new HashSet<>();
                ids.add(row.messageId());
                marks.put(key, new Mark(row.timestamp(), ids));
            } else if (row.timestamp() == mark.timestamp()) {
                mark.ids().add(row.messageId());
            }
        }

        /**
         * Record where each target's counter stands now, so the first delta measures
         * what arrived after the tail started rather than since the broker booted.
         */
        private void primeAdded() {
            lastAdded.putAll(addedByTarget());
        }

        private Map<String, Long> addedByTarget() {
            Set<String> wanted = new HashSet<>();
            plan.targets().forEach(t -> wanted.add(key(t)));
            Map<String, Long> added = new LinkedHashMap<>();
            for (QueueSnapshotEntity snapshot : snapshots.findByClusterId(clusterId)) {
                String key = key(snapshot.getNodeId(), snapshot.getQueueName());
                if (wanted.contains(key)) {
                    added.put(key, snapshot.getMessagesAdded());
                }
            }
            return added;
        }
    }

    /** The high-water mark: the newest timestamp seen, and the ids seen at exactly it. */
    private record Mark(long timestamp, Set<Long> ids) {}

    /**
     * Start tailing a plan that has already been permission-checked, cost-gated and
     * run once. The returned handle is the only way to stop it, and the caller must
     * stop it when its client disconnects.
     */
    public Tail start(UUID clusterId, QueryPlan plan, MessageTransport transport, Listener listener) {
        return start(clusterId, plan, transport, listener, Duration.ZERO, false);
    }

    /**
     * As above, but polled no more often than {@code minInterval}, and optionally
     * starting from the beginning of each queue rather than from now. The tick still
     * runs at the console's cadence; a tail that is not due is skipped.
     */
    public Tail start(
            UUID clusterId,
            QueryPlan plan,
            MessageTransport transport,
            Listener listener,
            Duration minInterval,
            boolean fromBeginning) {
        Tail tail = new Tail(clusterId, plan, transport, listener, minInterval, fromBeginning);
        tails.add(tail);
        return tail;
    }

    /** Registered with {@code DynamicSchedules} on the configured tail interval. */
    public void tick() {
        for (Tail tail : tails) {
            if (tail.cancelled()) {
                tails.remove(tail);
                continue;
            }
            // One poll per tail at a time. A tail whose poll is still running has a
            // slower broker than the interval assumes, and queueing more reads onto it
            // is the wrong answer.
            if (Instant.now().isBefore(tail.nextDue) || !tail.polling.compareAndSet(false, true)) {
                continue;
            }
            polls.execute(() -> {
                try {
                    poll(tail);
                } catch (RuntimeException e) {
                    log.debug("SQL tail poll failed", e);
                } finally {
                    tail.nextDue = Instant.now().plus(tail.minInterval);
                    tail.polling.set(false);
                }
            });
        }
    }

    /** How many tails are running. */
    public int activeTails() {
        return tails.size();
    }

    private void poll(Tail tail) {
        if (tail.cancelled()) {
            return;
        }
        executor.execute(tail.clusterId, tail.plan, tail.transport, new TailSink(tail), tail::selectorFor);
        tail.pollCount.incrementAndGet();
        measureGap(tail);
        tail.listener.status(new TailStatus(
                tail.enqueued.get(), tail.shown.get(), tail.pollCount.get(), Instant.now(), tail.everyMessageMatches));
    }

    /**
     * How many messages were enqueued on the tailed queues since the last poll, from
     * the {@code MessagesAdded} counters the scrape tiers already collect. A counter
     * that went backwards means the node restarted; that interval is not measurable,
     * so it counts as zero rather than as a negative or an absurd spike.
     */
    private void measureGap(Tail tail) {
        tail.addedByTarget().forEach((key, added) -> {
            Long previous = tail.lastAdded.put(key, added);
            if (previous != null && added > previous) {
                tail.enqueued.addAndGet(added - previous);
            }
        });
    }

    private static String key(Target target) {
        return key(target.nodeId(), target.queueName());
    }

    private static String key(UUID nodeId, String queueName) {
        return nodeId + " " + queueName;
    }

    /** Filters a poll's rows down to what is genuinely new, and advances the mark. */
    private final class TailSink implements BrokerQueryExecutor.Sink {
        private final Tail tail;

        private TailSink(Tail tail) {
            this.tail = tail;
        }

        @Override
        public void row(Row row) {
            tail.listener.observed(row);
            if (!tail.isNew(row)) {
                return;
            }
            tail.advance(key(row.nodeId(), row.queueName()), row);
            tail.shown.incrementAndGet();
            tail.listener.row(row);
        }

        @Override
        public void nodeFinished(NodeOutcome outcome) {
            // A node that stops answering mid-tail is the operator's business: the
            // rows they are watching stop arriving, and silence reads as "nothing is
            // being enqueued" rather than "this node is gone".
            if (outcome.status() != NodeOutcome.Status.ANSWERED) {
                tail.listener.nodeFinished(outcome);
            }
        }

        @Override
        public boolean isCancelled() {
            return tail.cancelled();
        }
    }
}
