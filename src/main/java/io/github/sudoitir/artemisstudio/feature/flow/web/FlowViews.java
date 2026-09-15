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
        CONSUMER,
        /** Outside what the graph's columns hold: another cluster node, or a bridge's remote target. */
        REMOTE
    }

    /** What a node stands for when it is not an ordinary client, address or queue. */
    public enum NodeRole {
        /** A queue a cluster connection moves messages through to another node. */
        STORE_AND_FORWARD,
        /** Several temporary queues of one client, collapsed into one node. */
        TEMPORARY,
        /** Where producers that name no address send: the address is chosen per message. */
        ANONYMOUS,
        /** A queue or address Studio's message capture owns. */
        CAPTURE,
        DEAD_LETTER,
        EXPIRY,
        /** A broker node of this cluster, reached through store-and-forward. */
        CLUSTER_NODE,
        /** A bridge target that is not an address of this cluster. */
        BRIDGE_TARGET
    }

    public enum EdgeKind {
        PRODUCE,
        ROUTE,
        CONSUME,
        /** An address's messages taken (exclusive) or copied to another address. Not counted by the broker. */
        DIVERT,
        /** A queue forwarded to an address, locally or on another broker. */
        BRIDGE,
        /** Messages a node moves to another node of the cluster. */
        CLUSTER_HOP,
        /** A concrete address whose messages also reach a wildcard address's queues. */
        WILDCARD,
        DEAD_LETTER,
        EXPIRY
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
        STALLED,
        /** A bridge that is not connected to its target on every node it is deployed on. */
        BRIDGE_DOWN,
        /** A divert or bridge deployed on only some of the sampled nodes. */
        PARTIAL_PRESENCE
    }

    /** A serving node's part in the latest sweep. */
    public enum NodeSampleState {
        OK,
        UNREACHABLE,
        PERMISSION_DENIED,
        COUNTER_UNAVAILABLE,
        ROUTING_UNAVAILABLE,
        FAILED
    }

    /**
     * @param role what the node stands for beyond its kind; null for an ordinary client, address or queue
     * @param members connections or consumers a client node stands for, or temporary queues a collapsed
     *     node stands for; null otherwise
     * @param messageCount waiting messages on a queue; null for other kinds and for a queue not swept yet
     * @param consumerCount consumers attached to a queue; null for other kinds
     * @param routingTypes an address's routing types; empty for other kinds
     * @param brokerNodes names of the nodes this resource or client was seen on
     */
    public record FlowNodeView(
            String id,
            NodeKind kind,
            NodeRole role,
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
     * @param members producers or consumers the edge aggregates; null otherwise
     * @param exclusive for a divert, whether it takes the message rather than copying it
     * @param filter a divert's, bridge's or filtered queue's selector
     * @param transformer a divert's or bridge's transformer class
     * @param bypassed for a route edge, an exclusive divert without a filter takes this address's
     *     messages before they reach the queue
     * @param presentOn nodes a divert or bridge is deployed on; null for other kinds
     * @param presentOf nodes sampled, against which {@code presentOn} is counted
     * @param studio the object is Studio's own (a capture tap)
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
            Boolean exclusive,
            String filter,
            String transformer,
            boolean bypassed,
            Integer presentOn,
            Integer presentOf,
            boolean studio,
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
     * @param layers the routing layers drawn
     * @param assumptions what the drawing assumes and cannot read from the broker, in words
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
            List<String> layers,
            List<String> assumptions,
            List<FlowBrokerNodeView> brokerNodes) {}
}
