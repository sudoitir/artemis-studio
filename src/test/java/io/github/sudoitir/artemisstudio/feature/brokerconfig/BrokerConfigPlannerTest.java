package io.github.sudoitir.artemisstudio.feature.brokerconfig;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.feature.brokerconfig.BrokerConfigDocument.AddressDecl;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.BrokerConfigDocument.AddressSettingDecl;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.BrokerConfigDocument.DivertDecl;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.BrokerConfigDocument.QueueDecl;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.BrokerConfigDocument.SecuritySettingDecl;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.ObservedNodeConfig.AddressUsage;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.Plan.FindingKind;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.Plan.HazardKind;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.Plan.Op;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.Plan.Section;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.Plan.Step;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The plan is diff-driven and ordered (ADR-0067 D3), discloses replace semantics
 * (D5), removes only what Studio applied and never a queue (D6), and names hazards
 * with stable identifiers (D7).
 */
class BrokerConfigPlannerTest {

    private static final UUID N1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID N2 = UUID.fromString("00000000-0000-0000-0000-000000000002");

    /** The dev broker's sparse {@code #} entry, as {@code getAddressSettingsAsJSON("#")} reports it. */
    private static Map<String, Object> base() {
        Map<String, Object> m = new HashMap<>();
        m.put("addressFullMessagePolicy", "PAGE");
        m.put("maxSizeBytes", -1L);
        m.put("pageSizeBytes", 10485760L);
        m.put("autoCreateQueues", true);
        m.put("deadLetterAddress", "DLQ");
        m.put("expiryAddress", "ExpiryQueue");
        return m;
    }

    private static ObservedNodeConfig node(
            UUID id,
            String name,
            Map<String, Map<String, Object>> settings,
            Map<String, DivertDecl> diverts,
            Map<String, AddressUsage> usage) {
        Map<String, Map<String, Object>> all = new HashMap<>(settings);
        all.putIfAbsent("#", base());
        return new ObservedNodeConfig(
                id,
                name,
                true,
                Map.of("orders.in", Set.of("ANYCAST"), "DLQ", Set.of("ANYCAST"), "ExpiryQueue", Set.of("ANYCAST")),
                Map.of(
                        "orders.in",
                        Map.of("name", "orders.in", "address", "orders.in", "routing-type", "ANYCAST", "durable", true),
                        "DLQ",
                        Map.of("name", "DLQ", "address", "DLQ", "routing-type", "ANYCAST", "durable", true)),
                all,
                Map.of("#", Map.of(PermissionType.SEND, Set.of("amq"))),
                diverts,
                usage,
                null);
    }

    private static BrokerConfigDocument doc(AddressSettingDecl... settings) {
        return new BrokerConfigDocument(1, List.of(), List.of(settings), List.of(), List.of());
    }

    private static List<Step> pending(Plan plan, UUID node) {
        return plan.nodes().stream().filter(n -> n.nodeId().equals(node)).findFirst().orElseThrow().steps().stream()
                .filter(s -> !s.already())
                .toList();
    }

    @Test
    void aMatchingNodeHasNoPendingStepsAndAnEmptyHashDiffersFromAPendingOne() {
        Map<String, Object> observed = base();
        observed.put("maxDeliveryAttempts", 3L);
        ObservedNodeConfig n = node(N1, "broker-1", Map.of("orders.#", observed), Map.of(), Map.of());
        AddressSettingDecl same = new AddressSettingDecl("orders.#", Map.of("maxDeliveryAttempts", 3));

        Plan plan = BrokerConfigPlanner.plan(doc(same), List.of(n), Set.of(), PlanOptions.defaults());

        assertThat(plan.valid()).isTrue();
        assertThat(plan.stepCount()).isZero();
        assertThat(plan.nodes().getFirst().steps())
                .singleElement()
                .satisfies(s -> assertThat(s.already()).isTrue());

        Plan changed = BrokerConfigPlanner.plan(
                doc(new AddressSettingDecl("orders.#", Map.of("maxDeliveryAttempts", 5))),
                List.of(n),
                Set.of(),
                PlanOptions.defaults());
        assertThat(changed.stepCount()).isEqualTo(1);
        assertThat(changed.planHash()).isNotEqualTo(plan.planHash());
    }

    @Test
    void replaceSemanticsAreDisclosedAsAnUnintendedChangePerKey() {
        Map<String, Object> observed = base();
        observed.put("maxDeliveryAttempts", 3L);
        observed.put("autoCreateQueues", false);
        ObservedNodeConfig n = node(N1, "broker-1", Map.of("orders.#", observed), Map.of(), Map.of());

        Plan plan = BrokerConfigPlanner.plan(
                doc(new AddressSettingDecl("orders.#", Map.of("redistributionDelay", 5))),
                List.of(n),
                Set.of(),
                PlanOptions.defaults());

        Step step = pending(plan, N1).getFirst();
        assertThat(step.op()).isEqualTo(Op.REPLACE);
        assertThat(step.before()).containsEntry("maxDeliveryAttempts", 3L);
        assertThat(step.after()).containsEntry("redistributionDelay", 5L).doesNotContainKey("maxDeliveryAttempts");
        assertThat(plan.hazards())
                .filteredOn(h -> h.kind() == HazardKind.UNINTENDED_KEY_CHANGE)
                .extracting(h -> h.key() + "/" + h.id().substring(h.id().lastIndexOf(':') + 1))
                .containsExactlyInAnyOrder("orders.#/maxDeliveryAttempts", "orders.#/autoCreateQueues");
        assertThat(plan.highHazardIds()).hasSize(2);
    }

    @Test
    void lossPoliciesAndLimitsBelowUsageAreHighHazardsNamingTheNode() {
        ObservedNodeConfig n =
                node(N1, "broker-1", Map.of(), Map.of(), Map.of("orders.in", new AddressUsage(50_000_000L, 1200L)));

        Plan plan = BrokerConfigPlanner.plan(
                doc(new AddressSettingDecl(
                        "orders.#", Map.of("addressFullMessagePolicy", "drop", "maxSizeBytes", 20_000_000))),
                List.of(n),
                Set.of(),
                PlanOptions.defaults());

        assertThat(plan.hazards())
                .extracting(h -> h.kind())
                .contains(HazardKind.MESSAGE_LOSS_POLICY, HazardKind.LIMIT_BELOW_USAGE);
        assertThat(plan.hazards())
                .filteredOn(h -> h.kind() == HazardKind.LIMIT_BELOW_USAGE)
                .singleElement()
                .satisfies(h -> assertThat(h.message())
                        .contains("orders.in")
                        .contains("broker-1")
                        .contains("50000000 bytes"));
        assertThat(pending(plan, N1).getFirst().after()).containsEntry("addressFullMessagePolicy", "DROP");
    }

    @Test
    void catchAllAndManagementCoveringSecurityMatchesAreHigh() {
        ObservedNodeConfig n = node(N1, "broker-1", Map.of(), Map.of(), Map.of());
        BrokerConfigDocument d = new BrokerConfigDocument(
                1,
                List.of(),
                List.of(),
                List.of(
                        new SecuritySettingDecl("#", Map.of(PermissionType.SEND, Set.of("app-role"))),
                        new SecuritySettingDecl(
                                "activemq.management.#", Map.of(PermissionType.MANAGE, Set.of("app-role")))),
                List.of());

        Plan plan = BrokerConfigPlanner.plan(d, List.of(n), Set.of(), PlanOptions.defaults());

        assertThat(plan.hazards())
                .extracting(h -> h.kind())
                .contains(HazardKind.BROAD_MATCH, HazardKind.MANAGEMENT_ACCESS);
        assertThat(plan.hazards())
                .filteredOn(h -> h.kind() == HazardKind.MANAGEMENT_ACCESS)
                .hasSize(2);
    }

    @Test
    void aDivertThatDiffersIsARemoveAndAnAddInThatOrder() {
        DivertDecl existing = new DivertDecl("audit", "orders.in", "DLQ", null, false, null, null, Map.of());
        ObservedNodeConfig n = node(N1, "broker-1", Map.of(), Map.of("audit", existing), Map.of());
        DivertDecl wanted = new DivertDecl("audit", "orders.in", "DLQ", null, true, null, null, Map.of());
        BrokerConfigDocument d = new BrokerConfigDocument(1, List.of(), List.of(), List.of(), List.of(wanted));

        Plan plan = BrokerConfigPlanner.plan(d, List.of(n), Set.of(), PlanOptions.defaults());

        assertThat(pending(plan, N1)).extracting(Step::op).containsExactly(Op.REMOVE, Op.ADD);
        assertThat(plan.hazards())
                .extracting(h -> h.kind())
                .containsExactlyInAnyOrder(HazardKind.DIVERT_REPLACE, HazardKind.EXCLUSIVE_DIVERT);
        assertThat(plan.hazards())
                .filteredOn(h -> h.kind() == HazardKind.EXCLUSIVE_DIVERT)
                .singleElement()
                .satisfies(h -> assertThat(h.hazardClass()).isEqualTo(HazardClass.HIGH));
    }

    @Test
    void aDivertToAnAddressNobodyConsumesIsAViolationNotAStep() {
        ObservedNodeConfig n = node(N1, "broker-1", Map.of(), Map.of(), Map.of());
        BrokerConfigDocument d = new BrokerConfigDocument(
                1,
                List.of(),
                List.of(),
                List.of(),
                List.of(new DivertDecl("d", "orders.in", "nowhere", null, false, null, null, Map.of())));

        Plan plan = BrokerConfigPlanner.plan(d, List.of(n), Set.of(), PlanOptions.defaults());

        assertThat(plan.valid()).isFalse();
        assertThat(plan.violations().getFirst().path()).isEqualTo("diverts[0].forwardingAddress");
        assertThat(plan.nodes()).isEmpty();
    }

    @Test
    void removesOnlyOwnedItemsAndNeverAQueue() {
        DivertDecl studioMade = new DivertDecl("mine", "orders.in", "DLQ", null, false, null, null, Map.of());
        DivertDecl foreign = new DivertDecl("theirs", "orders.in", "DLQ", null, false, null, null, Map.of());
        ObservedNodeConfig n = node(N1, "broker-1", Map.of(), Map.of("mine", studioMade, "theirs", foreign), Map.of());
        // The declaration names neither divert and no queues at all.
        BrokerConfigDocument empty = BrokerConfigDocument.empty();

        Plan plan = BrokerConfigPlanner.plan(
                empty, List.of(n), Set.of(new OwnedItem(Section.DIVERT, "mine")), PlanOptions.defaults());
        assertThat(pending(plan, N1)).singleElement().satisfies(s -> {
            assertThat(s.op()).isEqualTo(Op.REMOVE);
            assertThat(s.key()).isEqualTo("mine");
        });
        assertThat(plan.nodes().getFirst().steps()).noneMatch(s -> s.section() == Section.QUEUE);

        Plan optIn = BrokerConfigPlanner.plan(
                empty,
                List.of(n),
                Set.of(new OwnedItem(Section.DIVERT, "mine")),
                new PlanOptions(Set.of(), null, true, true, List.of("DLQ", "ExpiryQueue")));
        assertThat(pending(optIn, N1)).extracting(Step::key).containsExactly("mine", "theirs");
        assertThat(optIn.hazards())
                .filteredOn(h -> h.kind() == HazardKind.REMOVE_UNDECLARED)
                .singleElement()
                .satisfies(h -> assertThat(h.hazardClass()).isEqualTo(HazardClass.HIGH));
        assertThat(optIn.findings())
                .filteredOn(f -> f.kind() == FindingKind.UNDECLARED)
                .extracting(f -> f.section() + ":" + f.key())
                .containsExactly("ADDRESS:orders.in", "QUEUE:orders.in");
    }

    private static BrokerConfigDocument ordersQueue(QueueDecl queue) {
        return new BrokerConfigDocument(
                1,
                List.of(new AddressDecl("orders.in", Set.of("ANYCAST"), List.of(queue))),
                List.of(),
                List.of(),
                List.of());
    }

    private static BrokerConfigDocument address(String name, Set<String> routingTypes) {
        return new BrokerConfigDocument(
                1, List.of(new AddressDecl(name, routingTypes, List.of())), List.of(), List.of(), List.of());
    }

    /** A node holding exactly these addresses and queues, with the dev {@code #} entry. */
    private static ObservedNodeConfig holding(
            Map<String, Set<String>> addresses, Map<String, Map<String, Object>> queues) {
        return new ObservedNodeConfig(
                N1, "broker-1", true, addresses, queues, Map.of("#", base()), Map.of(), Map.of(), Map.of(), null);
    }

    @Test
    void anExistingQueueThatDiffersIsAReplaceStepThatApplyConverges() {
        // The live queue carries keys the declaration does not set; the step compares
        // only declared keys, so before lists no key the step does not change (ADR-0082 D1).
        Map<String, Object> live = new HashMap<>(
                Map.of("name", "orders.in", "address", "orders.in", "routing-type", "ANYCAST", "durable", true));
        live.put("exclusive", true);
        live.put("ring-size", -1L);
        live.put("max-consumers", -1);
        ObservedNodeConfig n = holding(Map.of("orders.in", Set.of("ANYCAST")), Map.of("orders.in", live));

        Plan plan = BrokerConfigPlanner.plan(
                ordersQueue(new QueueDecl("orders.in", "ANYCAST", "x = 1", true, 4, null, null, null, null)),
                List.of(n),
                Set.of(),
                PlanOptions.defaults());

        assertThat(plan.findings()).isEmpty();
        assertThat(plan.hazards()).isEmpty();
        assertThat(pending(plan, N1)).singleElement().satisfies(s -> {
            assertThat(s.section()).isEqualTo(Section.QUEUE);
            assertThat(s.op()).isEqualTo(Op.REPLACE);
            assertThat(s.key()).isEqualTo("orders.in");
            assertThat(s.after()).containsEntry("filter-string", "x = 1").containsEntry("max-consumers", 4);
            assertThat(s.after()).doesNotContainKey("auto-create-address");
            assertThat(s.after().keySet()).containsAll(s.before().keySet());
            assertThat(s.before()).containsEntry("max-consumers", -1).doesNotContainKey("exclusive");
            assertThat(s.description()).contains("filter-string").contains("max-consumers");
        });
    }

    @Test
    void aQueueKeyTheDeclarationDoesNotSetIsNeitherDriftNorChanged() {
        Map<String, Object> live = new HashMap<>(
                Map.of("name", "orders.in", "address", "orders.in", "routing-type", "ANYCAST", "durable", true));
        live.put("max-consumers", 3);
        live.put("filter-string", "region = 'eu'");
        ObservedNodeConfig n = holding(Map.of("orders.in", Set.of("ANYCAST")), Map.of("orders.in", live));

        Plan plan = BrokerConfigPlanner.plan(
                ordersQueue(new QueueDecl("orders.in", "ANYCAST", null, true, null, null, null, null, null)),
                List.of(n),
                Set.of(),
                PlanOptions.defaults());

        assertThat(plan.stepCount()).isZero();
        assertThat(plan.findings()).isEmpty();
    }

    @Test
    void aQueueThatDiffersInAFieldTheBrokerCannotChangeStaysAFindingWithNoStep() {
        ObservedNodeConfig n = node(N1, "broker-1", Map.of(), Map.of(), Map.of());

        // durable differs (immutable) and so does the filter (mutable): no partial update.
        Plan plan = BrokerConfigPlanner.plan(
                ordersQueue(new QueueDecl("orders.in", "ANYCAST", "x = 1", false, null, null, null, null, null)),
                List.of(n),
                Set.of(),
                PlanOptions.defaults());

        assertThat(plan.stepCount()).isZero();
        assertThat(plan.findings()).singleElement().satisfies(f -> {
            assertThat(f.kind()).isEqualTo(FindingKind.DIVERGENT_QUEUE);
            assertThat(f.detail()).contains("durable").contains("delete");
        });
    }

    @Test
    void addingARoutingTypeIsAReplaceStepWithALowHazard() {
        ObservedNodeConfig n = holding(Map.of("events", Set.of("ANYCAST")), Map.of());

        Plan plan = BrokerConfigPlanner.plan(
                address("events", Set.of("ANYCAST", "MULTICAST")), List.of(n), Set.of(), PlanOptions.defaults());

        assertThat(plan.findings()).isEmpty();
        assertThat(pending(plan, N1)).singleElement().satisfies(s -> {
            assertThat(s.section()).isEqualTo(Section.ADDRESS);
            assertThat(s.op()).isEqualTo(Op.REPLACE);
            assertThat(s.before()).containsEntry("routingTypes", List.of("ANYCAST"));
            assertThat(s.after()).containsEntry("routingTypes", List.of("ANYCAST", "MULTICAST"));
        });
        assertThat(plan.hazards()).singleElement().satisfies(h -> {
            assertThat(h.kind()).isEqualTo(HazardKind.ROUTING_TYPE_CHANGE);
            assertThat(h.hazardClass()).isEqualTo(HazardClass.LOW);
            assertThat(h.message()).contains("MULTICAST");
        });
    }

    @Test
    void aRoutingTypeWithQueuesBoundIsKeptAndReportedNotAStepTheBrokerRefuses() {
        ObservedNodeConfig n = holding(
                Map.of("events", Set.of("ANYCAST", "MULTICAST")),
                Map.of(
                        "events.work",
                        Map.of("name", "events.work", "address", "events", "routing-type", "ANYCAST"),
                        "events.audit",
                        Map.of("name", "events.audit", "address", "events", "routing-type", "MULTICAST")));

        // The broker refuses to drop ANYCAST while events.work is bound (AMQ229209), so a
        // step would only halt the run on this node before any other change.
        Plan plan = BrokerConfigPlanner.plan(
                address("events", Set.of("MULTICAST")), List.of(n), Set.of(), PlanOptions.defaults());

        assertThat(pending(plan, N1)).isEmpty();
        assertThat(plan.hazards()).isEmpty();
        assertThat(plan.findings()).singleElement().satisfies(f -> {
            assertThat(f.kind()).isEqualTo(FindingKind.DIVERGENT_ADDRESS);
            assertThat(f.section()).isEqualTo(Section.ADDRESS);
            assertThat(f.detail())
                    .contains("ANYCAST")
                    .contains("events.work")
                    .doesNotContain("events.audit")
                    .contains("delete");
        });
    }

    @Test
    void aBoundRoutingTypeIsKeptWhileTheRestOfTheAddressConverges() {
        ObservedNodeConfig n = holding(
                Map.of("events", Set.of("ANYCAST")),
                Map.of("events.work", Map.of("name", "events.work", "address", "events", "routing-type", "ANYCAST")));

        Plan plan = BrokerConfigPlanner.plan(
                address("events", Set.of("MULTICAST")), List.of(n), Set.of(), PlanOptions.defaults());

        assertThat(pending(plan, N1)).singleElement().satisfies(s -> {
            assertThat(s.op()).isEqualTo(Op.REPLACE);
            assertThat(s.after()).containsEntry("routingTypes", List.of("ANYCAST", "MULTICAST"));
        });
        assertThat(plan.hazards())
                .singleElement()
                .satisfies(h -> assertThat(h.hazardClass()).isEqualTo(HazardClass.LOW));
        assertThat(plan.findings())
                .singleElement()
                .satisfies(f -> assertThat(f.kind()).isEqualTo(FindingKind.DIVERGENT_ADDRESS));
    }

    @Test
    void removingARoutingTypeNoQueueIsBoundToIsLow() {
        ObservedNodeConfig n = holding(
                Map.of("events", Set.of("ANYCAST", "MULTICAST")),
                Map.of(
                        "events.audit",
                        Map.of("name", "events.audit", "address", "events", "routing-type", "MULTICAST")));

        Plan plan = BrokerConfigPlanner.plan(
                address("events", Set.of("MULTICAST")), List.of(n), Set.of(), PlanOptions.defaults());

        assertThat(pending(plan, N1)).hasSize(1);
        assertThat(plan.hazards())
                .singleElement()
                .satisfies(h -> assertThat(h.hazardClass()).isEqualTo(HazardClass.LOW));
    }

    @Test
    void driftReportsADivergentQueueAsDivergenceTheApplyCloses() {
        Map<String, Object> live = new HashMap<>(
                Map.of("name", "orders.in", "address", "orders.in", "routing-type", "ANYCAST", "durable", true));
        live.put("exclusive", true);
        ObservedNodeConfig n = holding(Map.of("orders.in", Set.of("ANYCAST")), Map.of("orders.in", live));
        Plan plan = BrokerConfigPlanner.plan(
                ordersQueue(new QueueDecl("orders.in", "ANYCAST", null, true, 1, null, null, null, null)),
                List.of(n),
                Set.of(),
                PlanOptions.drift(false, List.of()));

        BrokerConfigDriftService.NodeReport report = BrokerConfigDriftService.report(n, plan, 1, null);

        assertThat(report.findings()).singleElement().satisfies(f -> {
            assertThat(f.kind()).isEqualTo(FindingKind.DIVERGENT);
            assertThat(f.section()).isEqualTo(Section.QUEUE);
            assertThat(f.declared()).containsEntry("max-consumers", 1).doesNotContainKey("auto-create-address");
            // An undeclared live key is not drift (ADR-0082 D1).
            assertThat(f.observed()).doesNotContainKey("exclusive");
        });
    }

    @Test
    void stepsAreInDependencyOrderAndTheCanaryIsFirstByNameUnlessChosen() {
        ObservedNodeConfig a = node(N2, "broker-2", Map.of(), Map.of(), Map.of());
        ObservedNodeConfig b = node(N1, "broker-1", Map.of(), Map.of(), Map.of());
        BrokerConfigDocument d = new BrokerConfigDocument(
                1,
                List.of(new AddressDecl(
                        "orders.audit",
                        Set.of("ANYCAST"),
                        List.of(new QueueDecl("orders.audit", "ANYCAST", null, true, null, null, null, null, null)))),
                List.of(new AddressSettingDecl("orders.audit", Map.of("maxDeliveryAttempts", 1))),
                List.of(new SecuritySettingDecl("orders.audit", Map.of(PermissionType.CONSUME, Set.of("audit")))),
                List.of(new DivertDecl("audit", "orders.in", "orders.audit", null, false, null, null, Map.of())));

        Plan plan = BrokerConfigPlanner.plan(d, List.of(a, b), Set.of(), PlanOptions.defaults());

        assertThat(plan.valid()).isTrue();
        assertThat(pending(plan, N1))
                .extracting(Step::section)
                .containsExactly(
                        Section.ADDRESS,
                        Section.QUEUE,
                        Section.ADDRESS_SETTING,
                        Section.SECURITY_SETTING,
                        Section.DIVERT);
        assertThat(plan.canaryNodeId()).isEqualTo(N1);
        assertThat(plan.stepCount()).isEqualTo(10);

        Plan chosen = BrokerConfigPlanner.plan(
                d, List.of(a, b), Set.of(), new PlanOptions(Set.of(), N2, false, false, List.of()));
        assertThat(chosen.canaryNodeId()).isEqualTo(N2);
    }

    @Test
    void notLiveAndUnreachableNodesAreFindingsWithNoSteps() {
        ObservedNodeConfig backup = ObservedNodeConfig.notLive(N2, "broker-2");
        ObservedNodeConfig down = ObservedNodeConfig.unreachable(N1, "broker-1", "Connection refused.");

        Plan plan = BrokerConfigPlanner.plan(
                doc(new AddressSettingDecl("orders.#", Map.of("maxDeliveryAttempts", 1))),
                List.of(backup, down),
                Set.of(),
                PlanOptions.defaults());

        assertThat(plan.findings())
                .extracting(f -> f.kind())
                .containsExactlyInAnyOrder(FindingKind.NOT_EVALUATED, FindingKind.UNREACHABLE);
        assertThat(plan.stepCount()).isZero();
        assertThat(plan.canaryNodeId()).isNull();
    }

    @Test
    void securitySettingsAreComparedOnTheTypesTheBrokerReportsBack() {
        // view and edit are sent but never echoed (§15 M7): a declaration carrying them
        // must read as already applied once the ten echoed types match.
        UUID id = UUID.randomUUID();
        ObservedNodeConfig n = node(id, "broker-1", Map.of(), Map.of(), Map.of());
        Map<PermissionType, Set<String>> declared = new java.util.EnumMap<>(PermissionType.class);
        declared.put(PermissionType.SEND, Set.of("amq"));
        declared.put(PermissionType.VIEW, Set.of("amq"));
        declared.put(PermissionType.EDIT, Set.of("amq"));
        BrokerConfigDocument doc = new BrokerConfigDocument(
                1, List.of(), List.of(), List.of(new SecuritySettingDecl("#", declared)), List.of());

        Plan plan = BrokerConfigPlanner.plan(doc, List.of(n), Set.of(), PlanOptions.defaults());

        assertThat(pending(plan, id)).isEmpty();
    }

    @Test
    void restrictingAPlanToOneItemKeepsOnlyItsStepsAndItsOwnHazards() {
        // The screen's per-row "Apply this": one item, every node, and a hash over
        // exactly the steps that will run, so the confirmation matches the run.
        ObservedNodeConfig n =
                node(N1, "broker-1", Map.of(), Map.of(), Map.of("orders.in", new AddressUsage(50_000_000L, 1200L)));
        Plan full = BrokerConfigPlanner.plan(
                doc(
                        new AddressSettingDecl("orders.#", Map.of("addressFullMessagePolicy", "drop")),
                        new AddressSettingDecl("payments.#", Map.of("maxDeliveryAttempts", 7))),
                List.of(n),
                Set.of(),
                PlanOptions.defaults());
        assertThat(full.stepCount()).isEqualTo(2);

        Plan one = BrokerConfigPlanner.restrict(
                full, Set.of("ADDRESS_SETTING:payments.#:ADD", "ADDRESS_SETTING:payments.#:REPLACE"));

        assertThat(pending(one, N1)).extracting(Step::key).containsExactly("payments.#");
        assertThat(one.stepCount()).isEqualTo(1);
        assertThat(one.planHash()).isNotEqualTo(full.planHash());
        // The message-loss hazard belongs to the step that is no longer in the run:
        // asking for it to be acknowledged would confirm something that will not happen.
        assertThat(full.hazards()).extracting(Plan.Hazard::kind).contains(HazardKind.MESSAGE_LOSS_POLICY);
        assertThat(one.hazards()).isEmpty();
        assertThat(BrokerConfigPlanner.restrict(full, Set.of("ADDRESS_SETTING:orders.#:ADD"))
                        .hazards())
                .extracting(Plan.Hazard::key)
                .containsOnly("orders.#");
        assertThat(one.canaryNodeId()).isEqualTo(full.canaryNodeId());
    }

    @Test
    void anEmptyRestrictionIsTheWholePlan() {
        ObservedNodeConfig n = node(N1, "broker-1", Map.of(), Map.of(), Map.of());
        Plan full = BrokerConfigPlanner.plan(
                doc(new AddressSettingDecl("orders.#", Map.of("maxDeliveryAttempts", 7))),
                List.of(n),
                Set.of(),
                PlanOptions.defaults());

        assertThat(BrokerConfigPlanner.restrict(full, Set.of())).isEqualTo(full);
    }

    @Test
    void aRestrictionThatMatchesNothingIsAPlanWithNoSteps() {
        ObservedNodeConfig n = node(N1, "broker-1", Map.of(), Map.of(), Map.of());
        Plan full = BrokerConfigPlanner.plan(
                doc(new AddressSettingDecl("orders.#", Map.of("maxDeliveryAttempts", 7))),
                List.of(n),
                Set.of(),
                PlanOptions.defaults());

        Plan none = BrokerConfigPlanner.restrict(full, Set.of("ADDRESS_SETTING:nothing.#:ADD"));

        assertThat(none.stepCount()).isZero();
        assertThat(pending(none, N1)).isEmpty();
    }
}
