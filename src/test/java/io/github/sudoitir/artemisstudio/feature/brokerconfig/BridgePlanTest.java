package io.github.sudoitir.artemisstudio.feature.brokerconfig;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.feature.brokerconfig.BrokerConfigDocument.AddressDecl;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.BrokerConfigDocument.BridgeDecl;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.BrokerConfigDocument.QueueDecl;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.ObservedNodeConfig.ObservedBridge;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.Plan.FindingKind;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.Plan.HazardKind;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.Plan.Op;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.Plan.Section;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.Plan.Step;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.internal.persistence.BrokerConfigNodeStateEntity.Basis;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.internal.persistence.BrokerConfigNodeStateEntity.State;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Bridges are last in and first out (ADR-0091), a change is a removal and a creation
 * with its hazard named, and a bridge that matches but is not connected is a fault on
 * the broker rather than a difference from the declaration.
 */
class BridgePlanTest {

    private static final UUID N1 = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static BridgeDecl bridge(String forwardingAddress) {
        return new BridgeDecl(
                "orders-out",
                "orders.out",
                forwardingAddress,
                null,
                null,
                List.of("remote-a"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private static BrokerConfigDocument doc(BridgeDecl... bridges) {
        AddressDecl address = new AddressDecl(
                "orders.out",
                Set.of("ANYCAST"),
                List.of(new QueueDecl("orders.out", "ANYCAST", null, true, null, null, null, null, null)));
        return new BrokerConfigDocument(1, List.of(address), List.of(), List.of(), List.of(), List.of(bridges));
    }

    private static ObservedNodeConfig node(Map<String, ObservedBridge> bridges, boolean withQueue) {
        return node(bridges, withQueue, Map.of());
    }

    private static ObservedNodeConfig node(
            Map<String, ObservedBridge> bridges,
            boolean withQueue,
            Map<String, BrokerConfigDocument.DivertDecl> diverts) {
        return new ObservedNodeConfig(
                N1,
                "broker-1",
                true,
                Map.of("orders.out", Set.of("ANYCAST"), "orders.in", Set.of("ANYCAST")),
                withQueue
                        ? Map.of(
                                "orders.out",
                                Map.of(
                                        "name", "orders.out",
                                        "address", "orders.out",
                                        "routing-type", "ANYCAST",
                                        "durable", true),
                                "orders.in",
                                Map.of("name", "orders.in", "address", "orders.in"))
                        : Map.of("orders.in", Map.of("name", "orders.in", "address", "orders.in")),
                Map.of("#", Map.of()),
                Map.of(),
                diverts,
                bridges,
                Map.of(),
                null);
    }

    private static List<Step> steps(Plan plan) {
        return plan.nodes().stream()
                .filter(n -> n.nodeId().equals(N1))
                .flatMap(n -> n.steps().stream())
                .filter(s -> !s.already())
                .toList();
    }

    @Test
    void aBridgeIsCreatedAfterTheQueueItReadsFrom() {
        Plan plan = BrokerConfigPlanner.plan(
                doc(bridge("orders.in")), List.of(node(Map.of(), false)), Set.of(), PlanOptions.defaults());

        List<Step> pending = steps(plan);
        int queue = indexOf(pending, Section.QUEUE, Op.ADD);
        int bridge = indexOf(pending, Section.BRIDGE, Op.ADD);
        assertThat(queue).isGreaterThanOrEqualTo(0);
        assertThat(bridge).isGreaterThan(queue);
        assertThat(plan.hazards()).extracting(Plan.Hazard::kind).contains(HazardKind.BRIDGE_CREATE);
    }

    @Test
    void anOwnedBridgeIsRemovedBeforeTheDivertItSharesAnAddressWith() {
        BrokerConfigDocument empty = new BrokerConfigDocument(1, List.of(), List.of(), List.of(), List.of(), List.of());
        BrokerConfigDocument.DivertDecl divert = new BrokerConfigDocument.DivertDecl(
                "audit", "orders.in", "audit.in", null, false, null, null, Map.of());
        ObservedNodeConfig n = node(
                Map.of("orders-out", new ObservedBridge(bridge("orders.in"), true, true, 1)),
                true,
                Map.of("audit", divert));
        Set<OwnedItem> owned =
                Set.of(new OwnedItem(Section.BRIDGE, "orders-out"), new OwnedItem(Section.DIVERT, "audit"));

        List<Step> pending = steps(BrokerConfigPlanner.plan(empty, List.of(n), owned, PlanOptions.defaults()));

        assertThat(pending)
                .allMatch(s -> s.op() == Op.REMOVE)
                .extracting(Step::section)
                .containsExactly(Section.BRIDGE, Section.DIVERT);
    }

    @Test
    void removingABridgeIsAHighHazardThatNamesWhatStops() {
        BrokerConfigDocument empty = new BrokerConfigDocument(1, List.of(), List.of(), List.of(), List.of(), List.of());
        ObservedNodeConfig n = node(Map.of("orders-out", new ObservedBridge(bridge("orders.in"), true, true, 1)), true);

        Plan plan = BrokerConfigPlanner.plan(
                empty, List.of(n), Set.of(new OwnedItem(Section.BRIDGE, "orders-out")), PlanOptions.defaults());

        assertThat(plan.hazards())
                .filteredOn(h -> h.kind() == HazardKind.BRIDGE_REMOVE)
                .singleElement()
                .satisfies(h -> {
                    assertThat(h.hazardClass()).isEqualTo(HazardClass.HIGH);
                    assertThat(h.message()).contains("orders.out").contains("orders.in");
                });
    }

    @Test
    void aChangedBridgePlansAsARemovalThenACreationWithItsHazard() {
        ObservedNodeConfig n = node(Map.of("orders-out", new ObservedBridge(bridge("elsewhere"), true, true, 1)), true);

        Plan plan = BrokerConfigPlanner.plan(doc(bridge("orders.in")), List.of(n), Set.of(), PlanOptions.defaults());

        assertThat(steps(plan))
                .filteredOn(s -> s.section() == Section.BRIDGE)
                .extracting(Step::op)
                .containsExactly(Op.REMOVE, Op.ADD);
        assertThat(plan.hazards())
                .filteredOn(h -> h.kind() == HazardKind.BRIDGE_REPLACE)
                .singleElement()
                .satisfies(h -> {
                    assertThat(h.hazardClass()).isEqualTo(HazardClass.HIGH);
                    assertThat(h.message()).contains("forwarding-address").contains("nothing is forwarded");
                });
    }

    @Test
    void aMatchingBridgeThatIsNotConnectedIsAFaultAndNotDrift() {
        ObservedNodeConfig n =
                node(Map.of("orders-out", new ObservedBridge(bridge("orders.in"), true, false, 1)), true);

        Plan plan = BrokerConfigPlanner.plan(
                doc(bridge("orders.in")), List.of(n), Set.of(), PlanOptions.drift(false, List.of()));
        BrokerConfigDriftService.NodeReport report = BrokerConfigDriftService.report(n, plan, 3, Basis.OBSERVED_MATCH);

        assertThat(steps(plan)).filteredOn(s -> s.section() == Section.BRIDGE).isEmpty();
        assertThat(report.state()).isEqualTo(State.IN_SYNC);
        assertThat(report.findings()).singleElement().satisfies(f -> {
            assertThat(f.kind()).isEqualTo(FindingKind.NOT_CONNECTED);
            assertThat(f.detail()).contains("not connected to orders.in");
        });
        assertThat(report.detail()).contains("not forwarding");
    }

    @Test
    void aDivergentBridgeIsDriftRatherThanAFault() {
        ObservedNodeConfig n = node(Map.of("orders-out", new ObservedBridge(bridge("elsewhere"), true, true, 1)), true);

        Plan plan = BrokerConfigPlanner.plan(
                doc(bridge("orders.in")), List.of(n), Set.of(), PlanOptions.drift(false, List.of()));
        BrokerConfigDriftService.NodeReport report = BrokerConfigDriftService.report(n, plan, 3, Basis.OBSERVED_MATCH);

        assertThat(report.state()).isEqualTo(State.DRIFTED);
        assertThat(report.findings()).extracting(f -> f.kind()).doesNotContain(FindingKind.NOT_CONNECTED);
    }

    private static int indexOf(List<Step> steps, Section section, Op op) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).section() == section && steps.get(i).op() == op) {
                return i;
            }
        }
        return -1;
    }
}
