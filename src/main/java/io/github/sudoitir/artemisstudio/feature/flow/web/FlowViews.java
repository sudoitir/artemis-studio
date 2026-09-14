package io.github.sudoitir.artemisstudio.feature.flow.web;

import java.time.Instant;
import java.util.List;

/** The flow read's response (flow-visualization spec). Bounded server-side; every absent number is null, never 0. */
public final class FlowViews {

    private FlowViews() {}

    public enum NodeKind {
        PRODUCER,
        ADDRESS,
        QUEUE,
        CONSUMER
    }

    public enum EdgeKind {
        PRODUCE,
        ROUTE,
        CONSUME
    }

    /** Where an edge's rate came from. {@code NONE}: the broker keeps no count for it. */
    public enum RateSource {
        SAMPLER,
        QUEUE_METRIC,
        NONE
    }

    /** How an address hands messages to its queues. */
    public enum Delivery {
        /** Multicast: every bound queue receives a copy. */
        COPY,
        /** Anycast: the queues share the messages. */
        SHARED
    }

    public enum Fault {
        /** Messages are waiting on a queue that has no consumer. */
        NO_CONSUMER,
        /** Unacknowledged messages outstanding and nothing acknowledged for two sweeps. */
        STALLED
    }

    /** A serving node's part in the latest sweep. */
    public enum NodeSampleState {
        OK,
        UNREACHABLE,
        PERMISSION_DENIED,
        COUNTER_UNAVAILABLE,
        FAILED
    }

    /**
     * @param members connections or consumers a client node stands for; null for addresses and queues
     * @param messageCount waiting messages on a queue; null for other kinds
     * @param consumerCount consumers attached to a queue; null for other kinds
     * @param routingTypes an address's routing types; empty for other kinds
     * @param brokerNodes names of the nodes this resource or client was seen on
     */
    public record FlowNodeView(
            String id,
            NodeKind kind,
            String label,
            Integer members,
            Long messageCount,
            Long consumerCount,
            List<String> routingTypes,
            List<String> protocols,
            List<String> hosts,
            List<String> users,
            List<String> brokerNodes,
            List<Fault> faults) {}

    /**
     * @param rate messages per second, or null while not measurable
     * @param asOf when the rate was measured; null when it has not been
     * @param averagedOverSeconds set when the rate is an average over a span much longer than a sweep
     * @param stale the rate is older than three of its source's intervals
     * @param delivery for a route edge, whether its queue receives copies or shares
     * @param members producers or consumers the edge aggregates; null for a route
     */
    public record FlowEdgeView(
            String id,
            EdgeKind kind,
            String source,
            String target,
            Double rate,
            RateSource rateSource,
            Instant asOf,
            Long averagedOverSeconds,
            boolean stale,
            Delivery delivery,
            Integer members,
            List<Fault> faults) {}

    /** Totals over every path in the cluster, not only the shown ones. Rates are null when none is known. */
    public record FlowKpis(Double inRate, Double outRate, long backlog, int clients, int faults) {}

    /**
     * @param paths every address-to-queue path in the cluster, plus addresses produced to without a queue
     * @param shown the paths drawn
     * @param limit the bound in effect
     * @param clamped the request asked for more than the server allows
     */
    public record FlowTotals(int paths, int shown, int limit, boolean clamped) {}

    /**
     * @param producersTotal, consumersTotal what the node reported having; above the seen counts when truncated
     * @param brokerXmlSnippet for a refused permission, the management access that grants it
     */
    public record FlowBrokerNodeView(
            String nodeId,
            String name,
            NodeSampleState state,
            String message,
            Instant sampledAt,
            int producersSeen,
            int producersTotal,
            int consumersSeen,
            int consumersTotal,
            boolean truncated,
            String brokerXmlSnippet) {}

    /** @param matched false when the focus names nothing currently in the cluster */
    public record FlowFocusView(String kind, String name, int hops, boolean matched) {}

    /**
     * @param sampledAt the newest client sample; null before the first sweep
     * @param measuring no sweep has completed since this cluster became observed
     */
    public record FlowGraphView(
            List<FlowNodeView> nodes,
            List<FlowEdgeView> edges,
            FlowKpis kpis,
            FlowTotals totals,
            FlowFocusView focus,
            Instant sampledAt,
            boolean measuring,
            long sampleIntervalSeconds,
            List<FlowBrokerNodeView> brokerNodes) {}
}
