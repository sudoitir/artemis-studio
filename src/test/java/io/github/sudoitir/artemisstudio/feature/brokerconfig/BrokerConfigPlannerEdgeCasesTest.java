package io.github.sudoitir.artemisstudio.feature.brokerconfig;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.feature.brokerconfig.BrokerConfigDocument.AddressDecl;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.BrokerConfigDocument.AddressSettingDecl;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.BrokerConfigDocument.BridgeDecl;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.BrokerConfigDocument.DivertDecl;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.BrokerConfigDocument.QueueDecl;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.BrokerConfigDocument.TransformerDecl;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.ObservedNodeConfig.AddressUsage;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.ObservedNodeConfig.ObservedBridge;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.Plan.FindingKind;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.Plan.HazardKind;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.Plan.Op;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.Plan.Section;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.Plan.Step;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The planner's referential checks, hazards, removals and undeclared reporting beyond the main paths. */
class BrokerConfigPlannerEdgeCasesTest {

    private static final UUID N1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID N2 = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static Map<String, Object> base() {
        Map<String, Object> m = new HashMap<>();
        m.put("addressFullMessagePolicy", "PAGE");
        m.put("maxSizeBytes", -1L);
        m.put("pageSizeBytes", 10485760L);
        m.put("autoCreateQueues", true);
        return m;
    }

    /** Everything about one observed node that a test may want to vary, with sensible defaults. */
    private static final class Obs {
        UUID id = N1;
        String name = "broker-1";
        boolean live = true;
        String unavailable;
        Map<String, Set<String>> addresses = new LinkedHashMap<>(Map.of("orders.in", Set.of("ANYCAST")));
        Map<String, Map<String, Object>> queues = new LinkedHashMap<>(
                Map.of("orders.in", Map.of("name", "orders.in", "address", "orders.in", "routing-type", "ANYCAST")));
        Map<String, Map<String, Object>> settings = new LinkedHashMap<>(Map.of("#", base()));
        Map<String, Map<PermissionType, Set<String>>> security =
                new LinkedHashMap<>(Map.of("#", Map.of(PermissionType.SEND, Set.of("amq"))));
        Map<String, DivertDecl> diverts = new LinkedHashMap<>();
        Map<String, ObservedBridge> bridges = new LinkedHashMap<>();
        Map<String, AddressUsage> usage = new LinkedHashMap<>();

        Obs with(java.util.function.Consumer<Obs> change) {
            change.accept(this);
            return this;
        }

        ObservedNodeConfig build() {
            return new ObservedNodeConfig(
                    id, name, live, addresses, queues, settings, security, diverts, bridges, usage, unavailable);
        }
    }

    private static Obs obs() {
        return new Obs();
    }

    private static BrokerConfigDocument doc(
            List<AddressDecl> addresses,
            List<AddressSettingDecl> settings,
            List<DivertDecl> diverts,
            List<BridgeDecl> bridges) {
        return new BrokerConfigDocument(1, addresses, settings, List.of(), diverts, bridges);
    }

    private static BrokerConfigDocument settingsDoc(AddressSettingDecl... settings) {
        return doc(List.of(), List.of(settings), List.of(), List.of());
    }

    private static Plan plan(BrokerConfigDocument doc, ObservedNodeConfig node) {
        return BrokerConfigPlanner.plan(doc, List.of(node), Set.of(), PlanOptions.defaults());
    }

    private static List<Step> pending(Plan plan) {
        return plan.nodes().stream()
                .flatMap(n -> n.steps().stream())
                .filter(s -> !s.already())
                .toList();
    }

    /** The part of a hazard's identifier after its section and match: the key or address it is about. */
    private static String sub(Plan.Hazard h) {
        String prefix = h.kind() + ":" + h.nodeId() + ":" + h.section() + ":" + h.key();
        String rest = h.id().substring(prefix.length());
        return h.kind() + "/" + (rest.startsWith(":") ? rest.substring(1) : rest);
    }

    private static List<HazardKind> hazards(Plan plan) {
        return plan.hazards().stream().map(Plan.Hazard::kind).toList();
    }

    private static BridgeDecl bridge(String name, String queue, String forwarding) {
        return new BridgeDecl(
                name,
                queue,
                forwarding,
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

    private static BridgeDecl fullBridge() {
        return new BridgeDecl(
                "b",
                "orders.in",
                "remote.in",
                "color='red'",
                new TransformerDecl("com.example.T", Map.of("k", "v")),
                List.of("remote-a", "remote-b"),
                null,
                true,
                true,
                1000L,
                2.0,
                30000L,
                -1,
                5,
                1024,
                2048,
                4096,
                500L,
                60000L,
                "MULTICAST",
                2,
                "client-1",
                null);
    }

    private static AddressDecl ordersIn() {
        return new AddressDecl(
                "orders.in",
                Set.of("ANYCAST"),
                List.of(new QueueDecl("orders.in", "ANYCAST", null, true, null, null, null, null, null)));
    }

    // ---- referential checks ------------------------------------------------------

    @Test
    void aDeadLetterOrExpiryAddressThatNothingProvidesIsAViolationOnTheDeclaringKey() {
        ObservedNodeConfig node = obs().build();

        Plan dead = plan(settingsDoc(new AddressSettingDecl("orders.#", Map.of("deadLetterAddress", "GONE"))), node);
        assertThat(dead.valid()).isFalse();
        assertThat(dead.violations()).singleElement().satisfies(v -> {
            assertThat(v.path()).isEqualTo("addressSettings[0].values.deadLetterAddress");
            assertThat(v.message()).contains("dead-letter address 'GONE'").contains("neither declared");
        });

        Plan expiry = plan(settingsDoc(new AddressSettingDecl("orders.#", Map.of("expiryAddress", "GONE"))), node);
        assertThat(expiry.violations())
                .extracting(Violation::message)
                .singleElement()
                .satisfies(m -> assertThat(m).contains("expiry address 'GONE'"));
    }

    @Test
    void anAddressTheDeclarationOrTheBrokerOrAnAutoCreateSettingProvidesIsNotAViolation() {
        Obs present = obs().with(o -> o.addresses.put("DLQ", Set.of("ANYCAST")));
        assertThat(plan(
                                settingsDoc(new AddressSettingDecl("orders.#", Map.of("deadLetterAddress", "DLQ"))),
                                present.build())
                        .valid())
                .as("present on every node")
                .isTrue();

        BrokerConfigDocument declared = doc(
                List.of(new AddressDecl("DLQ", Set.of("ANYCAST"), List.of())),
                List.of(new AddressSettingDecl("orders.#", Map.of("deadLetterAddress", "DLQ"))),
                List.of(),
                List.of());
        assertThat(plan(declared, obs().build()).valid()).as("declared").isTrue();

        assertThat(plan(
                                settingsDoc(new AddressSettingDecl(
                                        "orders.#",
                                        Map.of("deadLetterAddress", "NEW", "autoCreateDeadLetterResources", true))),
                                obs().build())
                        .valid())
                .as("auto-create declared alongside")
                .isTrue();

        Obs autoCreated = obs().with(o -> {
            Map<String, Object> s = base();
            s.put("autoCreateDeadLetterResources", true);
            o.settings.put("orders.#", s);
        });
        assertThat(plan(
                                settingsDoc(new AddressSettingDecl("orders.#", Map.of("deadLetterAddress", "NEW"))),
                                autoCreated.build())
                        .valid())
                .as("auto-create observed on the match")
                .isTrue();
    }

    @Test
    void aBlankDeadLetterAddressAndAPlanWithNoReadableNodeHaveNothingToCheckAgainst() {
        assertThat(plan(
                                settingsDoc(new AddressSettingDecl("orders.#", Map.of("deadLetterAddress", " "))),
                                obs().build())
                        .violations())
                .isEmpty();

        Obs down = obs().with(o -> o.unavailable = "timeout");
        assertThat(plan(
                                settingsDoc(new AddressSettingDecl("orders.#", Map.of("deadLetterAddress", "GONE"))),
                                down.build())
                        .valid())
                .as("with no readable node the address is not observed anywhere")
                .isFalse();
    }

    @Test
    void aPageSizeAloneIsCheckedAgainstTheMaxSizeTheNodeAlreadyHas() {
        Obs node = obs().with(o -> {
            Map<String, Object> merged = base();
            merged.put("maxSizeBytes", 1000L);
            o.settings.put("orders.#", merged);
        });

        Plan plan = plan(settingsDoc(new AddressSettingDecl("orders.#", Map.of("pageSizeBytes", 2000))), node.build());

        assertThat(plan.violations()).singleElement().satisfies(v -> {
            assertThat(v.path()).isEqualTo("addressSettings[0].values.pageSizeBytes");
            assertThat(v.message()).contains("On broker-1").contains("(2000)").contains("(1000)");
        });
    }

    @Test
    void aMaxSizeAloneIsCheckedAgainstTheDefaultsPageSizeAndAnUnlimitedMaxIsFine() {
        Plan tooSmall =
                plan(settingsDoc(new AddressSettingDecl("orders.#", Map.of("maxSizeBytes", 100))), obs().build());
        assertThat(tooSmall.violations())
                .singleElement()
                .satisfies(v -> assertThat(v.path()).isEqualTo("addressSettings[0].values.maxSizeBytes"));

        assertThat(plan(settingsDoc(new AddressSettingDecl("orders.#", Map.of("maxSizeBytes", -1))), obs().build())
                        .valid())
                .isTrue();
        assertThat(plan(
                                settingsDoc(new AddressSettingDecl("orders.#", Map.of("maxSizeBytes", 999_999_999))),
                                obs().build())
                        .valid())
                .isTrue();
        assertThat(plan(
                                settingsDoc(new AddressSettingDecl("orders.#", Map.of("maxDeliveryAttempts", 3))),
                                obs().build())
                        .valid())
                .as("neither declared")
                .isTrue();
    }

    // ---- node selection -----------------------------------------------------------

    @Test
    void aRunNarrowedToOneNodeLeavesTheOthersOutOfThePlanAndTheCanaryIsTheRequestedOne() {
        ObservedNodeConfig a = obs().build();
        ObservedNodeConfig b = obs().with(o -> {
                    o.id = N2;
                    o.name = "broker-2";
                })
                .build();
        BrokerConfigDocument d = settingsDoc(new AddressSettingDecl("orders.#", Map.of("maxDeliveryAttempts", 3)));

        Plan narrowed = BrokerConfigPlanner.plan(
                d, List.of(a, b), Set.of(), new PlanOptions(Set.of(N2), N2, false, false, List.of()));
        assertThat(narrowed.nodes()).extracting(Plan.NodePlan::nodeId).containsExactly(N2);
        assertThat(narrowed.canaryNodeId()).isEqualTo(N2);

        Plan both = BrokerConfigPlanner.plan(
                d, List.of(a, b), Set.of(), new PlanOptions(Set.of(), N2, false, false, List.of()));
        assertThat(both.canaryNodeId()).isEqualTo(N2);

        Plan unknownCanary = BrokerConfigPlanner.plan(
                d, List.of(a, b), Set.of(), new PlanOptions(Set.of(), UUID.randomUUID(), false, false, List.of()));
        assertThat(unknownCanary.canaryNodeId()).isEqualTo(N1);
    }

    @Test
    void aPlanWithNoReadableNodeHasNoCanary() {
        Obs down = obs().with(o -> o.unavailable = "timeout");
        Obs standby = obs().with(o -> {
            o.id = N2;
            o.live = false;
        });

        Plan plan = BrokerConfigPlanner.plan(
                settingsDoc(), List.of(down.build(), standby.build()), Set.of(), PlanOptions.defaults());

        assertThat(plan.canaryNodeId()).isNull();
        assertThat(plan.findings())
                .extracting(Plan.Finding::kind)
                .contains(FindingKind.UNREACHABLE, FindingKind.NOT_EVALUATED);
    }

    @Test
    void restrictingAPlanKeepsOnlyTheNamedStepsAndTheirHazards() {
        Obs node = obs().with(o -> o.addresses.put("orders.out", Set.of("ANYCAST")));
        BrokerConfigDocument d = settingsDoc(
                new AddressSettingDecl("orders.#", Map.of("addressFullMessagePolicy", "DROP")),
                new AddressSettingDecl("billing.#", Map.of("maxDeliveryAttempts", 3)));
        Plan full = plan(d, node.build());

        assertThat(BrokerConfigPlanner.restrict(full, Set.of())).isSameAs(full);

        Plan narrowed = BrokerConfigPlanner.restrict(full, Set.of("ADDRESS_SETTING:billing.#:ADD"));
        assertThat(pending(narrowed)).extracting(Step::key).containsExactly("billing.#");
        assertThat(narrowed.hazards()).isEmpty();
        assertThat(narrowed.stepCount()).isEqualTo(1);
        assertThat(narrowed.planHash()).isNotEqualTo(full.planHash());
        assertThat(full.hazards()).isNotEmpty();
    }

    // ---- address-setting hazards -----------------------------------------------------

    @Test
    void theCatchAllAddressSettingReplacesTheDefaultEveryAddressInherits() {
        Plan plan = plan(settingsDoc(new AddressSettingDecl("#", Map.of("maxDeliveryAttempts", 3))), obs().build());

        assertThat(plan.hazards())
                .filteredOn(h -> h.kind() == HazardKind.BROAD_MATCH)
                .singleElement()
                .satisfies(h -> assertThat(h.message()).contains("catch-all"));
    }

    @Test
    void blockAndPageFullFailAreNamedAndOtherPoliciesAreNot() {
        Obs node = obs();

        Plan block = plan(
                settingsDoc(new AddressSettingDecl("orders.#", Map.of("addressFullMessagePolicy", "BLOCK"))),
                node.build());
        assertThat(block.hazards())
                .filteredOn(h -> h.kind() == HazardKind.BLOCKING_POLICY)
                .singleElement()
                .satisfies(h -> assertThat(h.message())
                        .contains("producers stall")
                        .contains("under the match on this node: orders.in"));

        Plan pageFail = plan(
                settingsDoc(new AddressSettingDecl("orders.#", Map.of("pageFullMessagePolicy", "FAIL"))), node.build());
        assertThat(pageFail.hazards())
                .filteredOn(h -> h.kind() == HazardKind.MESSAGE_LOSS_POLICY)
                .singleElement()
                .satisfies(h -> assertThat(h.message()).contains("page-full-policy becomes FAIL"));

        Plan fail = plan(
                settingsDoc(new AddressSettingDecl("orders.#", Map.of("addressFullMessagePolicy", "FAIL"))),
                node.build());
        assertThat(fail.hazards().getFirst().message()).contains("sends are refused");

        Plan page = plan(
                settingsDoc(new AddressSettingDecl("orders.#", Map.of("addressFullMessagePolicy", "PAGE"))),
                node.build());
        assertThat(hazards(page)).doesNotContain(HazardKind.BLOCKING_POLICY, HazardKind.MESSAGE_LOSS_POLICY);
    }

    @Test
    void aMatchCoveringNothingIsMediumAndSaysNoAddressIsUnderIt() {
        Plan plan = plan(
                settingsDoc(new AddressSettingDecl("nothing.#", Map.of("addressFullMessagePolicy", "DROP"))),
                obs().build());

        assertThat(plan.hazards())
                .filteredOn(h -> h.kind() == HazardKind.MESSAGE_LOSS_POLICY)
                .singleElement()
                .satisfies(h -> {
                    assertThat(h.hazardClass()).isEqualTo(HazardClass.MEDIUM);
                    assertThat(h.message()).contains("no address on this node is under the match yet");
                });
    }

    @Test
    void aLongCoveredListIsTruncatedToThreeAndCounted() {
        Obs node = obs().with(o -> {
            for (String a : List.of("orders.a", "orders.b", "orders.c", "orders.d", "orders.e")) {
                o.addresses.put(a, Set.of("ANYCAST"));
            }
        });

        Plan plan = plan(
                settingsDoc(new AddressSettingDecl("orders.#", Map.of("addressFullMessagePolicy", "DROP"))),
                node.build());

        assertThat(plan.hazards().getFirst().message()).contains("orders.a, orders.b, orders.c and 3 more");
    }

    @Test
    void redirectingDeadLettersExpiryAndTurningOnAutoDeleteAreMediumHazardsWhenAddressesAreCovered() {
        Obs node = obs().with(o -> {
            o.addresses.put("DLQ", Set.of("ANYCAST"));
            o.addresses.put("Expired", Set.of("ANYCAST"));
        });
        Map<String, Object> observedOrders = base();
        observedOrders.put("deadLetterAddress", "OLD");
        node.settings.put("orders.#", observedOrders);

        Plan plan = plan(
                settingsDoc(new AddressSettingDecl(
                        "orders.#",
                        Map.of(
                                "deadLetterAddress",
                                "DLQ",
                                "expiryAddress",
                                "Expired",
                                "autoDeleteQueues",
                                true,
                                "autoDeleteAddresses",
                                true,
                                "autoDeleteCreatedQueues",
                                false,
                                "redistributionDelay",
                                5))),
                node.build());

        assertThat(plan.hazards())
                .extracting(BrokerConfigPlannerEdgeCasesTest::sub)
                .contains(
                        "DLQ_EXPIRY_CHANGE/deadLetterAddress",
                        "DLQ_EXPIRY_CHANGE/expiryAddress",
                        "AUTO_DELETE_ENABLED/autoDeleteQueues",
                        "AUTO_DELETE_ENABLED/autoDeleteAddresses",
                        "REDISTRIBUTION_CHANGE/redistributionDelay")
                .doesNotContain("AUTO_DELETE_ENABLED/autoDeleteCreatedQueues");
        assertThat(plan.hazards())
                .filteredOn(h -> sub(h).equals("DLQ_EXPIRY_CHANGE/deadLetterAddress"))
                .singleElement()
                .satisfies(h -> assertThat(h.message()).contains("changes from OLD to DLQ"));
        assertThat(plan.hazards())
                .filteredOn(h -> sub(h).equals("DLQ_EXPIRY_CHANGE/expiryAddress"))
                .singleElement()
                .satisfies(h -> assertThat(h.message()).contains("changes from the broker's default to Expired"));
    }

    @Test
    void redirectHazardsAreSkippedWhenNoAddressIsUnderTheMatch() {
        Plan plan = plan(
                settingsDoc(new AddressSettingDecl(
                        "nothing.#", Map.of("autoDeleteQueues", true, "expiryAddress", "ExpiryQueue"))),
                obs().with(o -> o.addresses.put("ExpiryQueue", Set.of("ANYCAST")))
                        .build());

        assertThat(hazards(plan)).doesNotContain(HazardKind.AUTO_DELETE_ENABLED, HazardKind.DLQ_EXPIRY_CHANGE);
    }

    @Test
    void messageCountLimitsBelowWhatAnAddressHoldsAreHighAndOthersAreNot() {
        Obs node = obs().with(o -> {
            o.addresses.put("orders.busy", Set.of("ANYCAST"));
            o.usage.put("orders.in", new AddressUsage(10, 5000));
            o.usage.put("orders.busy", new AddressUsage(10, 10));
        });

        Plan plan = plan(
                settingsDoc(new AddressSettingDecl(
                        "orders.#", Map.of("maxSizeMessages", 100, "pageLimitMessages", 100, "pageLimitBytes", 5))),
                node.build());

        assertThat(plan.hazards())
                .filteredOn(h -> h.kind() == HazardKind.LIMIT_BELOW_USAGE)
                .extracting(BrokerConfigPlannerEdgeCasesTest::sub)
                .containsExactlyInAnyOrder(
                        "LIMIT_BELOW_USAGE/maxSizeMessages:orders.in",
                        "LIMIT_BELOW_USAGE/pageLimitMessages:orders.in",
                        "LIMIT_BELOW_USAGE/pageLimitBytes:orders.in",
                        "LIMIT_BELOW_USAGE/pageLimitBytes:orders.busy");
        assertThat(plan.hazards())
                .filteredOn(h -> h.id().endsWith("maxSizeMessages:orders.in"))
                .singleElement()
                .satisfies(h -> assertThat(h.message()).contains("5000 messages"));
    }

    @Test
    void anUnlimitedLimitOrAnAddressWithNoUsageReadingIsNotBelowUsage() {
        Obs node = obs().with(o -> o.usage.put("orders.in", new AddressUsage(1, 1)));

        Plan plan = plan(settingsDoc(new AddressSettingDecl("orders.#", Map.of("maxSizeMessages", -1))), node.build());
        assertThat(hazards(plan)).doesNotContain(HazardKind.LIMIT_BELOW_USAGE);

        Obs noUsage = obs();
        Plan none =
                plan(settingsDoc(new AddressSettingDecl("orders.#", Map.of("maxSizeMessages", 1))), noUsage.build());
        assertThat(hazards(none)).doesNotContain(HazardKind.LIMIT_BELOW_USAGE);
    }

    // ---- diverts ---------------------------------------------------------------------

    @Test
    void aDivertAlreadyDeployedAsDeclaredIsAnAlreadyStep() {
        DivertDecl d = new DivertDecl("audit", "orders.in", "DLQ", "color='red'", false, "ANYCAST", null, Map.of());
        Obs node = obs().with(o -> {
            o.diverts.put("audit", d);
            o.queues.put("DLQ", Map.of("name", "DLQ", "address", "DLQ"));
        });

        Plan plan = plan(doc(List.of(), List.of(), List.of(d), List.of()), node.build());

        assertThat(plan.nodes().getFirst().steps()).singleElement().satisfies(s -> {
            assertThat(s.already()).isTrue();
            assertThat(s.after())
                    .containsEntry("filter-string", "color='red'")
                    .containsEntry("routing-type", "ANYCAST");
        });
        assertThat(plan.stepCount()).isZero();
    }

    @Test
    void anExclusiveDivertIsHighWhenQueuesSitOnItsAddressAndMediumWhenNoneDo() {
        DivertDecl onQueue = new DivertDecl("hi", "orders.in", "DLQ", null, true, null, null, Map.of());
        DivertDecl onNothing = new DivertDecl("lo", "empty.addr", "DLQ", null, true, null, null, Map.of());
        Obs node = obs().with(o -> o.queues.put("DLQ", Map.of("name", "DLQ", "address", "DLQ")));

        Plan plan = plan(doc(List.of(), List.of(), List.of(onQueue, onNothing), List.of()), node.build());

        assertThat(plan.hazards())
                .filteredOn(h -> h.kind() == HazardKind.EXCLUSIVE_DIVERT)
                .extracting(h -> h.key() + "/" + h.hazardClass())
                .containsExactlyInAnyOrder("hi/HIGH", "lo/MEDIUM");
    }

    // ---- bridges ---------------------------------------------------------------------

    @Test
    void aNewBridgeWithATransformerAndEveryOptionIsPlannedWithItsFullConfiguration() {
        BridgeDecl b = fullBridge();
        BrokerConfigDocument d = doc(List.of(ordersIn()), List.of(), List.of(), List.of(b));

        Plan plan = plan(d, obs().build());

        Step step = pending(plan).stream()
                .filter(s -> s.section() == Section.BRIDGE)
                .findFirst()
                .orElseThrow();
        assertThat(step.op()).isEqualTo(Op.ADD);
        assertThat(step.after())
                .containsEntry("name", "b")
                .containsEntry("queue-name", "orders.in")
                .containsEntry("forwarding-address", "remote.in")
                .containsEntry("filter-string", "color='red'")
                .containsEntry("static-connectors", List.of("remote-a", "remote-b"))
                .containsEntry("ha", true)
                .containsEntry("use-duplicate-detection", true)
                .containsEntry("retry-interval", 1000L)
                .containsEntry("retry-interval-multiplier", 2.0)
                .containsEntry("max-retry-interval", 30000L)
                .containsEntry("initial-connect-attempts", -1)
                .containsEntry("reconnect-attempts", 5)
                .containsEntry("confirmation-window-size", 1024)
                .containsEntry("producer-window-size", 2048)
                .containsEntry("min-large-message-size", 4096)
                .containsEntry("check-period", 500L)
                .containsEntry("connection-ttl", 60000L)
                .containsEntry("routing-type", "MULTICAST")
                .containsEntry("concurrency", 2)
                .containsEntry("client-id", "client-1")
                .containsKey("transformer-configuration");
        assertThat(step.description()).contains("remote-a, remote-b");
        assertThat(hazards(plan)).contains(HazardKind.BRIDGE_CREATE, HazardKind.UNVERIFIABLE_TRANSFORMER);
    }

    @Test
    void aBridgeToADiscoveryGroupNamesItAndLeavesOutStaticConnectors() {
        BridgeDecl discovered = new BridgeDecl(
                "b",
                "orders.in",
                "remote.in",
                null,
                null,
                List.of(),
                "group-1",
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

        Plan plan = plan(doc(List.of(ordersIn()), List.of(), List.of(), List.of(discovered)), obs().build());

        Step step = pending(plan).stream()
                .filter(s -> s.section() == Section.BRIDGE)
                .findFirst()
                .orElseThrow();
        assertThat(step.description()).contains("discovery group group-1");
        assertThat(step.after())
                .containsEntry("discovery-group-name", "group-1")
                .doesNotContainKey("static-connectors");
    }

    @Test
    void aBridgeDeployedWithADifferentConcurrencyIsReplacedAndTheDifferenceNamed() {
        BridgeDecl declared = fullBridge();
        Obs node = obs().with(o -> o.bridges.put("b", new ObservedBridge(declared, true, true, 1)));

        Plan plan = plan(doc(List.of(ordersIn()), List.of(), List.of(), List.of(declared)), node.build());

        assertThat(pending(plan))
                .filteredOn(s -> s.section() == Section.BRIDGE)
                .extracting(Step::op)
                .containsExactly(Op.REMOVE, Op.ADD);
        assertThat(plan.hazards())
                .filteredOn(h -> h.kind() == HazardKind.BRIDGE_REPLACE)
                .singleElement()
                .satisfies(h -> assertThat(h.message()).contains("concurrency (1 deployed, 2 declared)"));
    }

    @Test
    void aBridgeThatMatchesEvenInConcurrencyIsAnAlreadyStep() {
        BridgeDecl declared = fullBridge();
        Obs node = obs().with(o -> o.bridges.put("b", new ObservedBridge(declared, true, true, 2)));

        Plan plan = plan(doc(List.of(ordersIn()), List.of(), List.of(), List.of(declared)), node.build());

        assertThat(pending(plan)).filteredOn(s -> s.section() == Section.BRIDGE).isEmpty();
        assertThat(plan.findings()).extracting(Plan.Finding::kind).doesNotContain(FindingKind.NOT_CONNECTED);
    }

    @Test
    void aMatchingBridgeThatIsNotStartedIsReportedAsNotStartedRatherThanNotConnected() {
        BridgeDecl declared = fullBridge();
        Obs node = obs().with(o -> o.bridges.put("b", new ObservedBridge(declared, false, false, 2)));

        Plan plan = plan(doc(List.of(ordersIn()), List.of(), List.of(), List.of(declared)), node.build());

        assertThat(plan.findings())
                .filteredOn(f -> f.kind() == FindingKind.NOT_CONNECTED)
                .singleElement()
                .satisfies(f -> assertThat(f.detail()).contains("not started, so nothing reaches"));
    }

    // ---- removals -------------------------------------------------------------------------

    @Test
    void ownedItemsThatLeftTheDeclarationAreRemovedInReverseDependencyOrderWithTheirHazards() {
        Obs node = obs().with(o -> {
            o.security.put("app.#", Map.of(PermissionType.SEND, Set.of("app")));
            Map<String, Object> explicit = base();
            explicit.put("maxDeliveryAttempts", 3L);
            o.settings.put("orders.#", explicit);
            o.bridges.put(
                    "gone-bridge", new ObservedBridge(bridge("gone-bridge", "orders.in", "remote.in"), true, true, 1));
            o.diverts.put(
                    "gone-divert",
                    new DivertDecl("gone-divert", "orders.in", "DLQ", null, false, null, null, Map.of()));
        });
        Set<OwnedItem> owned = Set.of(
                new OwnedItem(Section.BRIDGE, "gone-bridge"),
                new OwnedItem(Section.DIVERT, "gone-divert"),
                new OwnedItem(Section.SECURITY_SETTING, "app.#"),
                new OwnedItem(Section.ADDRESS_SETTING, "orders.#"));

        Plan plan = BrokerConfigPlanner.plan(settingsDoc(), List.of(node.build()), owned, PlanOptions.defaults());

        assertThat(pending(plan))
                .extracting(s -> s.section() + ":" + s.key())
                .containsExactly(
                        "BRIDGE:gone-bridge",
                        "DIVERT:gone-divert",
                        "SECURITY_SETTING:app.#",
                        "ADDRESS_SETTING:orders.#");
        assertThat(hazards(plan)).contains(HazardKind.BRIDGE_REMOVE, HazardKind.REMOVE_OWNED);
        assertThat(plan.hazards())
                .filteredOn(h -> h.kind() == HazardKind.REMOVE_OWNED)
                .hasSize(3);
    }

    @Test
    void ownedItemsAlreadyGoneOrEqualToTheInheritedDefaultAreNotRemovedAgain() {
        Obs node = obs().with(o -> {
            o.security.put("app.#", Map.of(PermissionType.SEND, Set.of("amq")));
            o.settings.put("orders.#", base());
        });
        Set<OwnedItem> owned = Set.of(
                new OwnedItem(Section.BRIDGE, "absent-bridge"),
                new OwnedItem(Section.DIVERT, "absent-divert"),
                new OwnedItem(Section.SECURITY_SETTING, "absent.#"),
                new OwnedItem(Section.SECURITY_SETTING, "app.#"),
                new OwnedItem(Section.ADDRESS_SETTING, "absent.#"),
                new OwnedItem(Section.ADDRESS_SETTING, "orders.#"));

        Plan plan = BrokerConfigPlanner.plan(settingsDoc(), List.of(node.build()), owned, PlanOptions.defaults());

        assertThat(pending(plan)).isEmpty();
    }

    @Test
    void removingUndeclaredRemovesBridgesAndDivertsStudioDidNotCreateWithoutTouchingOwnedOnesTwice() {
        Obs node = obs().with(o -> {
            o.bridges.put("foreign", new ObservedBridge(bridge("foreign", "orders.in", "remote.in"), true, true, 1));
            o.bridges.put("owned", new ObservedBridge(bridge("owned", "orders.in", "remote.in"), true, true, 1));
            o.diverts.put(
                    "foreign-divert",
                    new DivertDecl("foreign-divert", "orders.in", "DLQ", null, false, null, null, Map.of()));
            o.diverts.put(
                    "owned-divert",
                    new DivertDecl("owned-divert", "orders.in", "DLQ", null, false, null, null, Map.of()));
        });
        Set<OwnedItem> owned =
                Set.of(new OwnedItem(Section.BRIDGE, "owned"), new OwnedItem(Section.DIVERT, "owned-divert"));

        Plan plan = BrokerConfigPlanner.plan(
                settingsDoc(), List.of(node.build()), owned, new PlanOptions(Set.of(), null, true, false, List.of()));

        assertThat(pending(plan))
                .extracting(s -> s.section() + ":" + s.key() + ":" + s.op())
                .containsExactly(
                        "BRIDGE:owned:REMOVE",
                        "BRIDGE:foreign:REMOVE",
                        "DIVERT:owned-divert:REMOVE",
                        "DIVERT:foreign-divert:REMOVE");
        assertThat(plan.hazards())
                .filteredOn(h -> h.kind() == HazardKind.REMOVE_UNDECLARED)
                .extracting(Plan.Hazard::key)
                .contains("foreign-divert");
        assertThat(plan.hazards())
                .filteredOn(h -> h.kind() == HazardKind.BRIDGE_REMOVE)
                .hasSize(2);
    }

    // ---- undeclared reporting -----------------------------------------------------------------

    @Test
    void driftReportsWhatExistsButIsNotDeclaredSkippingSystemAddressesAndExclusions() {
        Obs node = obs().with(o -> {
            o.addresses.put("stray.addr", Set.of("ANYCAST"));
            o.addresses.put("activemq.notifications", Set.of("MULTICAST"));
            o.addresses.put("excluded.addr", Set.of("ANYCAST"));
            o.queues.put("stray.q", Map.of("name", "stray.q", "address", "stray.addr"));
            o.queues.put("noaddr.q", Map.of("name", "noaddr.q"));
            o.queues.put("$.artemis.internal.sf.x", Map.of("name", "$.artemis.internal.sf.x", "address", "stray.addr"));
            o.queues.put("excluded.q", Map.of("name", "excluded.q", "address", "excluded.addr"));
            o.diverts.put(
                    "stray-divert",
                    new DivertDecl("stray-divert", "orders.in", "DLQ", null, false, null, null, Map.of()));
            o.diverts.put(
                    "excluded-divert",
                    new DivertDecl("excluded-divert", "orders.in", "DLQ", null, false, null, null, Map.of()));
            o.bridges.put("stray-bridge", new ObservedBridge(bridge("stray-bridge", "orders.in", "r"), true, true, 1));
            o.bridges.put("decl", new ObservedBridge(bridge("decl", "orders.in", "r"), true, true, 1));
            o.bridges.put("decl-1", new ObservedBridge(bridge("decl-1", "orders.in", "r"), true, true, 1));
            o.bridges.put(
                    "excluded-bridge", new ObservedBridge(bridge("excluded-bridge", "orders.in", "r"), true, true, 1));
        });
        BrokerConfigDocument d =
                doc(List.of(ordersIn()), List.of(), List.of(), List.of(bridge("decl", "orders.in", "r")));

        Plan plan = BrokerConfigPlanner.plan(
                d,
                List.of(node.build()),
                Set.of(),
                PlanOptions.drift(true, List.of("excluded*", "excluded.#", "excluded-*")));

        assertThat(plan.findings())
                .filteredOn(f -> f.kind() == FindingKind.UNDECLARED)
                .extracting(f -> f.section() + ":" + f.key())
                .contains(
                        "ADDRESS:stray.addr",
                        "QUEUE:stray.q",
                        "QUEUE:noaddr.q",
                        "DIVERT:stray-divert",
                        "BRIDGE:stray-bridge")
                .doesNotContain(
                        "ADDRESS:activemq.notifications",
                        "QUEUE:$.artemis.internal.sf.x",
                        "BRIDGE:decl",
                        "BRIDGE:decl-1",
                        "QUEUE:orders.in");
        assertThat(plan.findings())
                .filteredOn(f -> f.section() == Section.QUEUE && f.key().equals("stray.q"))
                .singleElement()
                .satisfies(f -> assertThat(f.detail()).isEqualTo("Exists on stray.addr and is not declared."));
        assertThat(plan.findings())
                .filteredOn(f -> f.section() == Section.QUEUE && f.key().equals("noaddr.q"))
                .singleElement()
                .satisfies(f -> assertThat(f.detail()).isEqualTo("Exists and is not declared."));
    }

    @Test
    void undeclaredIsNotReportedUnlessAskedFor() {
        Obs node = obs().with(o -> o.addresses.put("stray.addr", Set.of("ANYCAST")));

        Plan plan = BrokerConfigPlanner.plan(
                settingsDoc(), List.of(node.build()), Set.of(), PlanOptions.drift(false, List.of()));

        assertThat(plan.findings()).isEmpty();
    }

    // ---- address settings that are already there or replace an explicit entry ------------------

    @Test
    void anObservedEntryEqualToTheInheritedDefaultIsAddedNotReplaced() {
        Obs node = obs().with(o -> o.settings.put("orders.#", base()));

        Plan plan =
                plan(settingsDoc(new AddressSettingDecl("orders.#", Map.of("maxDeliveryAttempts", 3))), node.build());

        assertThat(pending(plan).getFirst().op()).isEqualTo(Op.ADD);
    }

    @Test
    void anEntryWithADifferentNumberOfKeysOrValuesIsAnExplicitEntryAndIsReplaced() {
        Map<String, Object> differentSize = base();
        differentSize.put("maxDeliveryAttempts", 1L);
        Map<String, Object> differentValue = base();
        differentValue.put("pageSizeBytes", 1L);

        for (Map<String, Object> observed : List.of(differentSize, differentValue)) {
            Obs node = obs().with(o -> o.settings.put("orders.#", observed));
            Plan plan = plan(
                    settingsDoc(new AddressSettingDecl("orders.#", Map.of("redistributionDelay", 5))), node.build());
            assertThat(pending(plan).getFirst().op()).isEqualTo(Op.REPLACE);
        }
    }

    // ---- queue configuration ---------------------------------------------------------------------

    @Test
    void aQueueConfigurationCarriesEveryOptionThatWasSetAndNoneThatWasNot() {
        QueueDecl full = new QueueDecl("q", "anycast", "color='red'", false, 5, true, false, true, 100L);
        Map<String, Object> config = BrokerConfigPlanner.queueConfig("addr", full);

        assertThat(config)
                .containsEntry("name", "q")
                .containsEntry("address", "addr")
                .containsEntry("routing-type", "ANYCAST")
                .containsEntry("durable", false)
                .containsEntry("auto-create-address", false)
                .containsEntry("filter-string", "color='red'")
                .containsEntry("max-consumers", 5)
                .containsEntry("purge-on-no-consumers", true)
                .containsEntry("exclusive", false)
                .containsEntry("non-destructive", true)
                .containsEntry("ring-size", 100L);

        Map<String, Object> sparse = BrokerConfigPlanner.queueConfig(
                "addr", new QueueDecl("q", "MULTICAST", null, null, null, null, null, null, null));
        assertThat(sparse)
                .containsOnlyKeys("name", "address", "routing-type", "durable", "auto-create-address")
                .containsEntry("durable", true);
    }
}
