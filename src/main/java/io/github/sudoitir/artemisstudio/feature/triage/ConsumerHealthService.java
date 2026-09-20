package io.github.sudoitir.artemisstudio.feature.triage;

import io.github.sudoitir.artemisstudio.feature.events.BrokerEventService;
import io.github.sudoitir.artemisstudio.feature.events.web.EventViews.BrokerEventView;
import io.github.sudoitir.artemisstudio.feature.resources.CrossNodeAggregator;
import io.github.sudoitir.artemisstudio.feature.resources.web.ResourceViews.QueueNodeCell;
import io.github.sudoitir.artemisstudio.feature.resources.web.ResourceViews.QueueView;
import io.github.sudoitir.artemisstudio.feature.triage.ConsumerHealth.Source;
import io.github.sudoitir.artemisstudio.feature.triage.ConsumerHealth.Verdict;
import io.github.sudoitir.artemisstudio.kernel.core.PagedView;
import io.github.sudoitir.artemisstudio.kernel.core.ResourceQuery;
import io.github.sudoitir.artemisstudio.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.artemisstudio.kernel.security.Permissions;
import io.github.sudoitir.artemisstudio.platform.scrape.MetricSamples;
import io.github.sudoitir.artemisstudio.platform.scrape.MetricSamples.SubjectRate;
import io.github.sudoitir.artemisstudio.platform.scrape.ScrapeProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The consumer-health verdict ladder (ADR-0089) — the one place it is evaluated.
 *
 * <p>The screen, the REST API, the {@code diagnose} MCP tool and the {@code consumerHealth}
 * alert condition all read this service. That is the point: before it existed, the MCP
 * tool computed a depth trend from two points and a slow-consumer guess from a ×10 ratio
 * while {@code SlowConsumerCondition} computed a better answer from the same data, so the
 * console, an alert and an agent could each describe one queue differently.
 *
 * <p>Costs per request: one {@code queue_snapshot} read (already indexed by cluster),
 * three windowed {@code metric_sample} aggregates, and one bounded event page — a fixed
 * number of queries regardless of how many queues the cluster has, and <em>no broker
 * call at all</em>. Reading health must never be a reason a broker falls over
 * (non-negotiable #1).
 */
@Service
@RequiredArgsConstructor
public class ConsumerHealthService {

    private static final String DEPTH = "messageCount";
    private static final String ADDED = "messagesAdded";
    private static final String ACKED = "messagesAcked";
    private static final String BROKER_SLOW_EVENT = "CONSUMER_SLOW";

    /** How many broker slow-consumer notifications to consider. Bounded; the newest win. */
    private static final int BROKER_SLOW_CAP = 200;

    private final CrossNodeAggregator queues;
    private final MetricSamples series;
    private final BrokerEventService brokerEvents;
    private final ScrapeProperties scrape;
    private final ConsumerHealthProperties properties;
    private final ClusterAccessGuard clusterAccess;

    /**
     * Every queue in the cluster, ranked worst-first unless the caller asked for another
     * order. A request path, so it checks {@code cluster:read} before reading anything.
     */
    @Transactional(readOnly = true)
    public PagedView<ConsumerHealth> page(UUID clusterId, ResourceQuery query) {
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_READ);
        List<ConsumerHealth> rows = evaluate(clusterId);
        List<ConsumerHealth> matched = rows.stream()
                .filter(h -> query.matches(h.queueName()) || query.matches(h.address()))
                .toList();
        if (query.sortField() == null) {
            // Worst first, then the biggest backlog, then by name so the order is stable
            // across refreshes of an unchanged cluster.
            List<ConsumerHealth> ranked = matched.stream().sorted(rankOrder()).toList();
            return new ResourceQuery(query.q(), query.page(), query.size(), null).paginate(ranked, null);
        }
        return query.paginate(matched, comparatorFor(query.sortField()));
    }

    /** One queue's verdict, for the drawer panel and the {@code diagnose} tool. */
    @Transactional(readOnly = true)
    public java.util.Optional<ConsumerHealth> forQueue(UUID clusterId, String queueName) {
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_READ);
        return evaluate(clusterId).stream()
                .filter(h -> h.queueName().equals(queueName))
                .findFirst();
    }

    /**
     * The ladder, applied to every queue in the cluster from one set of windowed reads.
     *
     * <p>The window is two tier-B intervals, matching {@code RateCondition} and
     * {@code SlowConsumerCondition} — wide enough that a queue swept on the slow tier has
     * two samples in it, narrow enough that the rate still describes now.
     */
    @Transactional(readOnly = true)
    public List<ConsumerHealth> evaluate(UUID clusterId) {
        // Unguarded on purpose: this is also the scheduler's path. The alert evaluator
        // runs on a scrape thread with no authenticated principal, so a permission check
        // here protects nothing and fails as a 404 about a cluster that plainly exists.
        // The guard belongs at the request boundary — page() and forQueue() apply it —
        // which is the same split the other alert conditions make by reading
        // queue_snapshot directly.
        List<QueueView> rows = queues.rollUp(clusterId);

        Instant to = Instant.now();
        Instant from = to.minus(scrape.tierBInterval().multipliedBy(2));

        Map<String, SubjectRate> addRates = series.latestRateWithTimeBySubject(clusterId, ADDED, from, to);
        Map<String, SubjectRate> ackRates = series.latestRateWithTimeBySubject(clusterId, ACKED, from, to);
        Map<String, Double> slopes = series.depthSlopeBySubject(clusterId, DEPTH, from, to);
        Map<String, BrokerEventView> brokerSlow = brokerSlowByAddress(clusterId, to);

        return rows.stream()
                .map(row -> classify(row, addRates, ackRates, slopes, brokerSlow))
                .toList();
    }

    private ConsumerHealth classify(
            QueueView row,
            Map<String, SubjectRate> addRates,
            Map<String, SubjectRate> ackRates,
            Map<String, Double> slopes,
            Map<String, BrokerEventView> brokerSlow) {

        SubjectRate add = addRates.get(row.queueName());
        SubjectRate ack = ackRates.get(row.queueName());
        Double slope = slopes.get(row.queueName());

        Double addRate = add == null ? null : add.rate();
        Double ackRate = ack == null ? null : ack.rate();
        Double netRate = addRate != null && ackRate != null ? addRate - ackRate : null;
        Double perConsumer =
                ackRate != null && row.totalConsumerCount() > 0 ? ackRate / row.totalConsumerCount() : null;

        // The newest sample across both counters, so the reported age is the age of the
        // freshest thing the verdict rests on, not of an arbitrary one of the two.
        Instant asOf = newest(add, ack);
        Duration span = longestSpan(add, ack);
        boolean stale = row.perNode().stream().anyMatch(QueueNodeCell::stale);

        Builder b = new Builder(row, addRate, ackRate, netRate, perConsumer, slope, asOf, span, stale);

        boolean backlog = row.totalMessageCount() >= properties.minBacklog();
        boolean acking = ackRate != null && ackRate > properties.idleAckRate();

        // 1. No computable rate. Checked first: every verdict below reasons about rates,
        //    and guessing from a depth alone is how "looks healthy" happens.
        if (ackRate == null && addRate == null) {
            return b.verdict(
                    Verdict.INSUFFICIENT_DATA,
                    "Not enough samples yet to measure throughput. A rate needs two sweeps of this"
                            + " queue; one more sweep will settle it.");
        }

        // 2. Paused. Ahead of every consumer verdict: a paused queue holds a backlog with
        //    no acknowledgements by design, which is indistinguishable from a stall by
        //    signal alone and entirely different in what the operator should do.
        if (row.paused()) {
            boolean everywhere = row.perNode().stream().allMatch(QueueNodeCell::paused);
            return b.verdict(
                    Verdict.PAUSED,
                    everywhere
                            ? "Paused, so nothing is being dispatched. The backlog is expected until it is resumed."
                            : "Paused on some nodes but not others. Resume it, or pause it everywhere —"
                                    + " a partial pause routes unevenly.");
        }

        // 3. Nothing attached at all.
        if (row.totalConsumerCount() == 0 && backlog) {
            return b.verdict(
                    Verdict.NO_CONSUMERS,
                    "No consumers are attached, so nothing is draining this queue. Start or fix the"
                            + " consuming application.");
        }

        // 4. The broker's own verdict, which outranks anything derived (ADR-0044).
        BrokerEventView slow = brokerSlow.get(row.address());
        if (slow != null && row.totalConsumerCount() > 0) {
            return b.broker(slow.consumerName())
                    .verdict(
                            Verdict.BROKER_SLOW,
                            "The broker's own slow-consumer detection fired for this address"
                                    + (slow.consumerName() == null ? "" : " (consumer " + slow.consumerName() + ")")
                                    + ". Its threshold is authoritative; check that consumer.");
        }

        // 5/6. Attached, backed up, not acknowledging. What is in flight separates a
        //      consumer that is holding messages from one that is being sent none, and
        //      the two lead to opposite investigations.
        if (row.totalConsumerCount() > 0 && backlog && !acking) {
            if (row.totalDeliveringCount() > 0) {
                return b.verdict(
                        Verdict.STALLED,
                        row.totalDeliveringCount()
                                + " messages are dispatched and unacknowledged while nothing is being"
                                + " acknowledged. The consumer is blocked, or a message it cannot process"
                                + " is being redelivered — take a thread dump of the consumer.");
            }
            return b.verdict(
                    Verdict.STARVED,
                    "Consumers are attached but nothing is being dispatched to them. Check for a"
                            + " message selector or filter that matches nothing, and whether the"
                            + " consumers are in a transaction they never commit.");
        }

        // 7. Acknowledging, but losing ground.
        if (netRate != null && netRate > 0 && backlog && rising(slope)) {
            return b.verdict(
                    Verdict.FALLING_BEHIND,
                    String.format(
                            "Arriving at %.2f msg/s and acknowledged at %.2f msg/s, so the backlog grows by"
                                    + " %.2f msg/s. Add consumer capacity, or slow the producers.",
                            addRate, ackRate, netRate));
        }

        // 8. Recovering. Reported rather than hidden: an operator watching an incident
        //    needs to know it is ending, and when.
        if (netRate != null && netRate < 0 && backlog) {
            Duration eta = Duration.ofSeconds((long) (row.totalMessageCount() / -netRate));
            return b.eta(eta)
                    .verdict(
                            Verdict.DRAINING,
                            String.format(
                                    "Draining at %.2f msg/s net. At this rate the backlog clears in about %s.",
                                    -netRate, humanise(eta)));
        }

        return b.verdict(Verdict.HEALTHY, "Keeping up with what arrives.");
    }

    /** A slope is only evidence of growth when it is measurable and positive. */
    private static boolean rising(Double slope) {
        return slope == null || slope > 0;
    }

    /**
     * The most recent broker slow-consumer notification per address, within the
     * configured window. One bounded page for the whole cluster rather than one query per
     * queue.
     */
    private Map<String, BrokerEventView> brokerSlowByAddress(UUID clusterId, Instant now) {
        Map<String, BrokerEventView> byAddress = new HashMap<>();
        // Unguarded, like the rest of this path: it is also the scheduler's.
        List<BrokerEventView> recent = brokerEvents.recentOfType(
                clusterId, BROKER_SLOW_EVENT, now.minus(properties.brokerSlowWindow()), now, BROKER_SLOW_CAP);
        for (BrokerEventView e : recent) {
            if (e.address() == null) {
                continue;
            }
            byAddress.merge(e.address(), e, (a, bEvent) -> a.occurredAt().isAfter(bEvent.occurredAt()) ? a : bEvent);
        }
        return byAddress;
    }

    private static Instant newest(SubjectRate a, SubjectRate b) {
        if (a == null) {
            return b == null ? null : b.asOf();
        }
        if (b == null) {
            return a.asOf();
        }
        return a.asOf().isAfter(b.asOf()) ? a.asOf() : b.asOf();
    }

    private static Duration longestSpan(SubjectRate a, SubjectRate b) {
        if (a == null) {
            return b == null ? null : b.span();
        }
        if (b == null) {
            return a.span();
        }
        return a.span().compareTo(b.span()) >= 0 ? a.span() : b.span();
    }

    private static String humanise(Duration d) {
        long minutes = d.toMinutes();
        if (minutes < 1) {
            return d.toSeconds() + "s";
        }
        if (minutes < 60) {
            return minutes + "m";
        }
        long hours = d.toHours();
        return hours < 48 ? hours + "h" : d.toDays() + "d";
    }

    /**
     * Worst first. Severity decides, then the size of the backlog behind it, then the
     * name — so an unchanged cluster lists in an unchanged order, and paging through it
     * cannot show the same queue twice.
     *
     * <p>{@code INSUFFICIENT_DATA} sorts immediately below the real faults and above
     * everything healthy: it is not a problem, but it is not an assurance either, and
     * folding it in with {@code HEALTHY} would let an unsampled queue read as a checked one.
     */
    private static Comparator<ConsumerHealth> rankOrder() {
        return Comparator.<ConsumerHealth>comparingInt(h -> -h.verdict().severity())
                .thenComparing(h -> h.verdict() == Verdict.INSUFFICIENT_DATA ? 0 : 1)
                .thenComparing(Comparator.comparingLong(ConsumerHealth::depth).reversed())
                .thenComparing(ConsumerHealth::queueName);
    }

    private static Comparator<ConsumerHealth> comparatorFor(String field) {
        return switch (field) {
            case "queueName" -> Comparator.comparing(ConsumerHealth::queueName);
            case "address" -> Comparator.comparing(ConsumerHealth::address);
            case "depth" -> Comparator.comparingLong(ConsumerHealth::depth);
            case "consumers" -> Comparator.comparingLong(ConsumerHealth::consumers);
            case "delivering" -> Comparator.comparingLong(ConsumerHealth::delivering);
            case "severity" -> Comparator.comparingInt(h -> h.verdict().severity());
            default -> null;
        };
    }

    /**
     * Carries the evidence common to every branch so each rung of the ladder states only
     * its verdict and its reason. Without it every {@code return} repeats twenty
     * arguments, and the ladder — the part worth reading — disappears into them.
     */
    private static final class Builder {
        private final QueueView row;
        private final Double addRate;
        private final Double ackRate;
        private final Double netRate;
        private final Double perConsumer;
        private final Double slope;
        private final Instant asOf;
        private final Duration span;
        private final boolean stale;
        private String brokerConsumerName;
        private Duration eta;

        private Builder(
                QueueView row,
                Double addRate,
                Double ackRate,
                Double netRate,
                Double perConsumer,
                Double slope,
                Instant asOf,
                Duration span,
                boolean stale) {
            this.row = row;
            this.addRate = addRate;
            this.ackRate = ackRate;
            this.netRate = netRate;
            this.perConsumer = perConsumer;
            this.slope = slope;
            this.asOf = asOf;
            this.span = span;
            this.stale = stale;
        }

        private Builder broker(String consumerName) {
            this.brokerConsumerName = consumerName;
            return this;
        }

        private Builder eta(Duration value) {
            this.eta = value;
            return this;
        }

        private ConsumerHealth verdict(Verdict verdict, String cause) {
            return new ConsumerHealth(
                    row.address(),
                    row.queueName(),
                    verdict,
                    cause,
                    verdict == Verdict.BROKER_SLOW ? Source.BROKER : Source.DERIVED,
                    brokerConsumerName,
                    row.totalMessageCount(),
                    row.totalConsumerCount(),
                    row.totalDeliveringCount(),
                    row.totalScheduledCount(),
                    row.paused(),
                    slope,
                    addRate,
                    ackRate,
                    netRate,
                    perConsumer,
                    eta,
                    asOf,
                    span,
                    stale,
                    row.nodesPresent(),
                    row.nodesTotal());
        }
    }
}
