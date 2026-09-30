package io.github.sudoitir.artemisstudio.platform.broker;

import io.github.sudoitir.artemisstudio.platform.broker.BrokerCapabilities.CapabilityStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * An operation Studio offers only on nodes whose broker release has it (ADR-0142).
 *
 * <p>{@link #ALL} is empty: everything Studio does works across the whole supported
 * range, and the one operation newer than the minimum, the JSON {@code createDivert},
 * falls back to its positional arm on older brokers. An operation that cannot fall
 * back is added here with the first release that has it; the UI then disables its
 * control with the release named, and {@code BrokerCommands} skips older nodes.
 *
 * <p>Gating is per node: a mixed cluster, as during a rolling upgrade, keeps the
 * operation for the nodes that support it.
 *
 * @param feature a stable id the UI finds the gate by
 * @param label what the operation does, as an operator reads it: "Creating a divert"
 * @param required the first release with the operation
 */
public record VersionGate(String feature, String label, BrokerVersion required) {

    /** Every gated operation. */
    public static final List<VersionGate> ALL = List.of();

    /** One node's verdict; {@code supported} is true when its version is unknown, so the broker decides. */
    public record NodeVerdict(UUID nodeId, String nodeName, String version, boolean supported) {}

    /**
     * The gate across a cluster, in the shape of a capability assessment so the UI
     * gates the control the same way it gates every other one.
     */
    public record Assessment(
            String feature,
            String label,
            String requiredVersion,
            CapabilityStatus status,
            String reason,
            List<NodeVerdict> nodes) {}

    /** Why a node running {@code version} cannot do this, or null when it can or its version is unknown. */
    public String refusal(String version) {
        Optional<BrokerVersion> v = BrokerVersion.parse(version);
        if (v.isEmpty() || v.get().atLeast(required)) {
            return null;
        }
        return label + " needs Artemis " + required + "; this node runs " + v.get() + ".";
    }

    /** The gate over the nodes that take commands: the active ones, or every node when none is. */
    public Assessment assess(List<NodeEndpoint> endpoints) {
        List<NodeEndpoint> targets =
                endpoints.stream().filter(NodeEndpoint::active).toList();
        if (targets.isEmpty()) {
            targets = endpoints;
        }
        List<NodeVerdict> nodes = targets.stream()
                .map(n -> new NodeVerdict(n.id(), n.name(), n.version(), refusal(n.version()) == null))
                .toList();
        List<NodeVerdict> known = nodes.stream()
                .filter(n -> BrokerVersion.parse(n.version()).isPresent())
                .toList();
        String needs = label + " needs Artemis " + required + " or later.";
        if (known.isEmpty()) {
            return verdict(CapabilityStatus.UNKNOWN, needs + " No node has reported its version yet.", nodes);
        }
        List<NodeVerdict> older = known.stream().filter(n -> !n.supported()).toList();
        if (older.isEmpty()) {
            return verdict(CapabilityStatus.AVAILABLE, needs + " Every node runs a release that has it.", nodes);
        }
        String olderNames =
                older.stream().map(n -> n.nodeName() + " (" + n.version() + ")").collect(Collectors.joining(", "));
        if (older.size() == known.size()) {
            return verdict(CapabilityStatus.UNAVAILABLE, needs + " This cluster runs " + olderNames + ".", nodes);
        }
        return verdict(
                CapabilityStatus.AVAILABLE,
                needs + " It is applied on the other nodes and skipped on " + olderNames + ".",
                nodes);
    }

    private Assessment verdict(CapabilityStatus status, String reason, List<NodeVerdict> nodes) {
        return new Assessment(feature, label, required.toString(), status, reason, nodes);
    }

    /** Every gate over the same nodes. */
    public static List<Assessment> assessAll(List<NodeEndpoint> endpoints) {
        return ALL.stream().map(g -> g.assess(endpoints)).toList();
    }
}
