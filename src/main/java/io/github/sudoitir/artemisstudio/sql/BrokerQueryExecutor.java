package io.github.sudoitir.artemisstudio.sql;

import io.github.sudoitir.artemisstudio.broker.BrokerConnectionException;
import io.github.sudoitir.artemisstudio.broker.MessageBrowser;
import io.github.sudoitir.artemisstudio.broker.MessageBrowser.BrowsedMessage;
import io.github.sudoitir.artemisstudio.broker.MessageTransport;
import io.github.sudoitir.artemisstudio.broker.MessageTransport.BrowseResult;
import io.github.sudoitir.artemisstudio.broker.MessageTransport.Channel;
import io.github.sudoitir.artemisstudio.broker.MessageTransport.TransportTarget;
import io.github.sudoitir.artemisstudio.config.ArtemisStudioProperties;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeRepository;
import io.github.sudoitir.artemisstudio.scheduler.NodeCallLimiter;
import io.github.sudoitir.artemisstudio.sql.MessagePredicate.EvalContext;
import io.github.sudoitir.artemisstudio.sql.PredicateSplitter.Split;
import io.github.sudoitir.artemisstudio.sql.QueryAst.Source;
import io.github.sudoitir.artemisstudio.sql.QueryPlan.Notice;
import io.github.sudoitir.artemisstudio.sql.QueryPlan.Target;
import io.github.sudoitir.artemisstudio.sql.QueryResult.Bound;
import io.github.sudoitir.artemisstudio.sql.QueryResult.NodeOutcome;
import io.github.sudoitir.artemisstudio.sql.QueryResult.Row;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Runs a {@link QueryPlan} against the live brokers (ADR-0058).
 *
 * <p>Nothing here is a new way to read a broker: it is the existing
 * {@link MessageTransport#browse} loop, one virtual thread per node, behind the same
 * {@link NodeCallLimiter} permit every other management call takes. That is what
 * makes non-negotiable #1 hold by construction — a runaway query throttles itself
 * against the broker rather than the other way round.
 *
 * <p>Four bounds stop it: targets, messages examined, rows returned, and wall clock.
 * Every one of them is reported when reached, because a bounded result that does not
 * say it is bounded is read as a complete one.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BrokerQueryExecutor {

    /** The broker will not return more than this per browse, whatever we ask for. */
    private static final int PAGE_SIZE = MessageBrowser.BROKER_PAGE_CAP;

    private final BrokerNodeRepository nodes;
    private final NodeCallLimiter limiter;
    private final MessagePredicate residuals;
    private final QueryPlanner planner;
    private final ArtemisStudioProperties properties;

    /** Where a caller receives rows and per-node outcomes as they happen. */
    public interface Sink {
        void row(Row row);

        void nodeFinished(NodeOutcome outcome);

        /** True once the caller has gone away, so no further broker read is issued. */
        boolean isCancelled();
    }

    public QueryResult execute(UUID clusterId, QueryPlan plan, MessageTransport transport, Sink sink) {
        return execute(clusterId, plan, transport, sink, target -> null);
    }

    /**
     * As {@link #execute(UUID, QueryPlan, MessageTransport, Sink)}, with one extra
     * selector clause per target — the tail's high-water mark (ADR-0058 D7).
     *
     * <p>It is ANDed into the pushed-down selector rather than filtered in Studio, so
     * the broker skips what has already been seen. Filtering here instead would mean
     * re-reading an entire queue every few seconds, which is exactly the thing
     * non-negotiable #1 exists to prevent.
     */
    public QueryResult execute(
            UUID clusterId,
            QueryPlan plan,
            MessageTransport transport,
            Sink sink,
            Function<Target, String> extraSelector) {
        ArtemisStudioProperties.Sql limits = properties.sql();
        Split split = planner.splitOf(plan.ast());

        Map<UUID, BrokerNodeEntity> nodesById = new LinkedHashMap<>();
        nodes.findByClusterIdOrderByNameAsc(clusterId).forEach(n -> nodesById.put(n.getId(), n));

        // Shared across the fan-out: rows and the examined counter are what the
        // bounds are measured against, so every node has to see the same ones.
        List<Row> rows = java.util.Collections.synchronizedList(new ArrayList<>());
        List<NodeOutcome> outcomes = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Bound> bounds = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Notice> notices = new ArrayList<>(plan.notices());

        AtomicLong examined = new AtomicLong();
        AtomicBoolean truncationSeen = new AtomicBoolean();
        long deadline = System.nanoTime() + limits.timeout().toNanos();

        // Grouped by node, and one virtual thread per node. Within a node the rate
        // limiter serialises the calls anyway, so parallelism there would buy
        // nothing; across nodes it is the difference between a wildcard query over a
        // cluster finishing inside the timeout and not.
        Map<UUID, List<Target>> byNode = new LinkedHashMap<>();
        for (Target target : plan.targets()) {
            byNode.computeIfAbsent(target.nodeId(), k -> new ArrayList<>()).add(target);
        }

        try (var scope = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<?>> running = new ArrayList<>();
            for (List<Target> nodeTargets : byNode.values()) {
                running.add(scope.submit(() -> {
                    for (Target target : nodeTargets) {
                        if (sink.isCancelled()) {
                            return;
                        }
                        Bound stop = boundReached(rows.size(), examined.get(), deadline, limits);
                        if (stop != null) {
                            recordBound(bounds, stop);
                            NodeOutcome skipped = new NodeOutcome(
                                    target.nodeId(),
                                    target.nodeName(),
                                    target.queueName(),
                                    NodeOutcome.Status.NOT_READ,
                                    0,
                                    0,
                                    null,
                                    "Stopped before this queue was read: " + describe(stop) + ".");
                            outcomes.add(skipped);
                            sink.nodeFinished(skipped);
                            continue;
                        }
                        BrokerNodeEntity node = nodesById.get(target.nodeId());
                        if (node == null) {
                            continue;
                        }
                        NodeOutcome outcome = readTarget(
                                clusterId,
                                plan,
                                split,
                                transport,
                                node,
                                target,
                                sink,
                                rows,
                                examined,
                                truncationSeen,
                                deadline,
                                limits,
                                bounds,
                                extraSelector);
                        outcomes.add(outcome);
                        sink.nodeFinished(outcome);
                    }
                }));
            }
            for (java.util.concurrent.Future<?> future : running) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    recordBound(
                            bounds,
                            new Bound(Bound.Kind.TIMEOUT, limits.timeout().toSeconds()));
                    break;
                } catch (java.util.concurrent.ExecutionException e) {
                    log.debug("SQL console fan-out task failed", e.getCause());
                }
            }
        }

        if (truncationSeen.get() && split.requiresScan()) {
            // A body predicate over a truncated body can be a false negative, and a
            // false negative during an incident reads as "the message is not there".
            notices.add(new Notice(
                    Notice.Kind.BODY_TRUNCATED,
                    "Some bodies were truncated by the broker's management-message-attribute-size-limit,"
                            + " so a body predicate may have missed matches. A Core connection returns full bodies."));
        }
        // Nodes race each other to the row limit, so the result can overshoot it by
        // less than a page. Trim to exactly what was asked for, and report the bound
        // whenever the limit was actually reached — stopping at the limit and not
        // saying so is how a partial result gets read as a complete one.
        List<Row> capped = new ArrayList<>(rows);
        if (capped.size() >= plan.effectiveLimit()) {
            capped = capped.subList(0, plan.effectiveLimit());
            recordBound(bounds, new Bound(Bound.Kind.ROW_LIMIT, plan.effectiveLimit()));
        }
        return new QueryResult(List.copyOf(capped), List.copyOf(outcomes), List.copyOf(bounds), List.copyOf(notices));
    }

    // ---- one target ----------------------------------------------------

    private NodeOutcome readTarget(
            UUID clusterId,
            QueryPlan plan,
            Split split,
            MessageTransport transport,
            BrokerNodeEntity node,
            Target target,
            Sink sink,
            List<Row> rows,
            AtomicLong examined,
            AtomicBoolean truncationSeen,
            long deadline,
            ArtemisStudioProperties.Sql limits,
            List<Bound> bounds,
            Function<Target, String> extraSelector) {

        // The selector is re-rendered per node, because a relative window means
        // something different on a node whose clock is measurably offset (ADR-0053).
        String selector = and(planner.selectorFor(split.pushdown(), target.brokerNow()), extraSelector.apply(target));
        java.util.function.Predicate<BrowsedMessage> residual = residuals.compile(
                split.scan(),
                new EvalContext(target.queueName(), target.address(), target.nodeName(), target.brokerNow()));

        TransportTarget transportTarget = new TransportTarget(
                clusterId,
                node.getId(),
                target.queueName(),
                target.address(),
                target.routingType(),
                node.getJolokiaUrl(),
                node.getCoreUrl());

        long matched = 0;
        long examinedHere = 0;
        Channel servedBy = null;
        int page = 1;

        while (true) {
            if (sink.isCancelled()) {
                return new NodeOutcome(
                        node.getId(),
                        target.nodeName(),
                        target.queueName(),
                        NodeOutcome.Status.NOT_READ,
                        examinedHere,
                        matched,
                        servedBy,
                        "The query was abandoned.");
            }
            Bound stop = boundReached(rows.size(), examined.get(), deadline, limits);
            if (stop != null) {
                recordBound(bounds, stop);
                break;
            }

            BrowseResult result;
            try {
                limiter.acquire(node.getId());
                result = transport.browse(transportTarget, page, PAGE_SIZE, selector);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new NodeOutcome(
                        node.getId(),
                        target.nodeName(),
                        target.queueName(),
                        NodeOutcome.Status.FAILED,
                        examinedHere,
                        matched,
                        servedBy,
                        "Timed out waiting for a rate-limit permit for this node.");
            } catch (BrokerConnectionException e) {
                return new NodeOutcome(
                        node.getId(),
                        target.nodeName(),
                        target.queueName(),
                        NodeOutcome.Status.FAILED,
                        examinedHere,
                        matched,
                        servedBy,
                        e.getMessage());
            } catch (RuntimeException e) {
                log.debug("SQL console browse of {} failed", target.queueName(), e);
                return new NodeOutcome(
                        node.getId(),
                        target.nodeName(),
                        target.queueName(),
                        NodeOutcome.Status.FAILED,
                        examinedHere,
                        matched,
                        servedBy,
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }

            if (servedBy != null && servedBy != result.servedBy()) {
                // Core has no server-side offset, so a deep page silently changes
                // fidelity. The result says so rather than mixing two truths.
                log.debug("SQL console channel changed mid-query on {}", target.queueName());
            }
            servedBy = result.servedBy();

            List<BrowsedMessage> messages = result.page().messages();
            for (BrowsedMessage message : messages) {
                examinedHere++;
                examined.incrementAndGet();
                if (message.bodyTruncated()) {
                    truncationSeen.set(true);
                }
                if (!residual.test(message)) {
                    continue;
                }
                matched++;
                Row row = toRow(message, node, target, result.servedBy());
                rows.add(row);
                sink.row(row);
                if (rows.size() >= plan.effectiveLimit()) {
                    break;
                }
            }
            if (messages.size() < PAGE_SIZE || rows.size() >= plan.effectiveLimit()) {
                break;
            }
            page++;
        }

        return new NodeOutcome(
                node.getId(),
                target.nodeName(),
                target.queueName(),
                NodeOutcome.Status.ANSWERED,
                examinedHere,
                matched,
                servedBy,
                null);
    }

    // ---- bounds ---------------------------------------------------------

    /** One entry per kind; a bound reached on three nodes is still one bound. */
    private void recordBound(List<Bound> bounds, Bound bound) {
        synchronized (bounds) {
            if (bounds.stream().noneMatch(b -> b.kind() == bound.kind())) {
                bounds.add(bound);
            }
        }
    }

    private Bound boundReached(int rows, long examined, long deadline, ArtemisStudioProperties.Sql limits) {
        if (System.nanoTime() > deadline) {
            return new Bound(Bound.Kind.TIMEOUT, limits.timeout().toSeconds());
        }
        if (examined >= limits.scanCap()) {
            return new Bound(Bound.Kind.SCAN_CAP, limits.scanCap());
        }
        if (rows >= limits.maxRows()) {
            return new Bound(Bound.Kind.ROW_LIMIT, limits.maxRows());
        }
        return null;
    }

    /** Both clauses, parenthesised, so operator precedence cannot change either one's meaning. */
    private String and(String selector, String extra) {
        if (extra == null || extra.isBlank()) {
            return selector;
        }
        if (selector == null || selector.isBlank()) {
            return extra;
        }
        return "(" + selector + ") AND (" + extra + ")";
    }

    private String describe(Bound bound) {
        return switch (bound.kind()) {
            case SCAN_CAP -> "the scan cap of " + bound.value() + " messages was reached";
            case ROW_LIMIT -> "the row limit of " + bound.value() + " was reached";
            case TIMEOUT -> "the query timed out after " + bound.value() + "s";
            case TARGET_CAP -> "the target cap of " + bound.value() + " queues was reached";
        };
    }

    // ---- mapping --------------------------------------------------------

    private Row toRow(BrowsedMessage message, BrokerNodeEntity node, Target target, Channel servedBy) {
        Map<String, Object> properties = new HashMap<>();
        properties.putAll(message.stringProperties());
        properties.putAll(message.intProperties());
        properties.putAll(message.longProperties());
        properties.putAll(message.doubleProperties());
        properties.putAll(message.booleanProperties());
        return new Row(
                node.getId(),
                target.nodeName(),
                target.queueName(),
                target.address(),
                message.messageId(),
                message.type(),
                message.durable(),
                message.priority(),
                message.timestamp(),
                message.expiration(),
                message.size(),
                message.contentType(),
                message.correlationId(),
                message.groupId(),
                message.userId(),
                message.replyTo(),
                message.body(),
                message.bodyTruncated(),
                Map.copyOf(properties),
                Source.BROKER,
                null,
                null,
                null,
                null);
    }

    /** A sink that only collects, for callers that want the whole result at once. */
    public static final class CollectingSink implements Sink {
        private final ConcurrentLinkedQueue<Row> rows = new ConcurrentLinkedQueue<>();
        private volatile boolean cancelled;

        @Override
        public void row(Row row) {
            rows.add(row);
        }

        @Override
        public void nodeFinished(NodeOutcome outcome) {
            // Collected from the returned QueryResult instead.
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        public void cancel() {
            cancelled = true;
        }

        public Optional<Row> first() {
            return Optional.ofNullable(rows.peek());
        }
    }
}
