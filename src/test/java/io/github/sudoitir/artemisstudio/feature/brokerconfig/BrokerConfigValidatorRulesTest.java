package io.github.sudoitir.artemisstudio.feature.brokerconfig;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.feature.brokerconfig.BrokerConfigDocument.AddressDecl;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.BrokerConfigDocument.AddressSettingDecl;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.BrokerConfigDocument.BridgeDecl;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.BrokerConfigDocument.DivertDecl;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.BrokerConfigDocument.QueueDecl;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.BrokerConfigDocument.SecuritySettingDecl;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.BrokerConfigDocument.TransformerDecl;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Each structural rule of the declaration, one violation path per case. */
class BrokerConfigValidatorRulesTest {

    private static List<String> paths(BrokerConfigDocument doc) {
        return BrokerConfigValidator.validate(doc).stream().map(Violation::path).toList();
    }

    private static BrokerConfigDocument addresses(AddressDecl... a) {
        return new BrokerConfigDocument(1, List.of(a), List.of(), List.of(), List.of(), List.of());
    }

    private static BrokerConfigDocument settings(AddressSettingDecl... s) {
        return new BrokerConfigDocument(1, List.of(), List.of(s), List.of(), List.of(), List.of());
    }

    private static BrokerConfigDocument security(SecuritySettingDecl... s) {
        return new BrokerConfigDocument(1, List.of(), List.of(), List.of(s), List.of(), List.of());
    }

    private static BrokerConfigDocument diverts(DivertDecl... d) {
        return new BrokerConfigDocument(1, List.of(), List.of(), List.of(), List.of(d), List.of());
    }

    private static BrokerConfigDocument bridges(BridgeDecl... b) {
        return new BrokerConfigDocument(1, List.of(), List.of(), List.of(), List.of(), List.of(b));
    }

    private static QueueDecl queue(
            String name, String routingType, Integer maxConsumers, Long ringSize, String filter) {
        return new QueueDecl(name, routingType, filter, true, maxConsumers, null, null, null, ringSize);
    }

    private static Map<String, Object> value(String key, Object v) {
        Map<String, Object> m = new HashMap<>();
        m.put(key, v);
        return m;
    }

    /** A valid bridge with one static connector; tests override one field at a time. */
    private static BridgeDecl bridge(
            String name, String queue, String forwarding, List<String> connectors, String discovery) {
        return new BridgeDecl(
                name,
                queue,
                forwarding,
                null,
                null,
                connectors,
                discovery,
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

    private static BridgeDecl validBridge() {
        return bridge("b", "q", "fwd", List.of("c1"), null);
    }

    // ---- addresses ---------------------------------------------------------

    @Test
    void addressNamesAreRequiredUniqueAndConcrete() {
        BrokerConfigDocument doc = addresses(
                new AddressDecl(" ", Set.of("ANYCAST"), List.of()),
                new AddressDecl("dup", Set.of("ANYCAST"), List.of()),
                new AddressDecl("dup", Set.of("ANYCAST"), List.of()),
                new AddressDecl("${addr}", Set.of("ANYCAST"), List.of()));

        assertThat(paths(doc)).containsExactly("addresses[0].name", "addresses[2].name", "addresses[3].name");
    }

    @Test
    void addressRoutingTypesAreRequiredAndKnown() {
        BrokerConfigDocument doc = addresses(
                new AddressDecl("none", Set.of(), List.of()), new AddressDecl("bad", Set.of("BOGUS"), List.of()));

        assertThat(paths(doc)).containsExactly("addresses[0].routingTypes", "addresses[1].routingTypes");
    }

    @Test
    void queueRulesCoverNameRoutingLimitsAndPlaceholders() {
        BrokerConfigDocument doc = addresses(new AddressDecl(
                "a",
                Set.of("ANYCAST", "MULTICAST"),
                List.of(
                        queue("", "ANYCAST", null, null, null),
                        queue("q1", null, -2, -2L, "x = '${v}'"),
                        queue("q2", "BOGUS", null, null, null),
                        queue("q3", "anycast", -1, -1L, null))));

        assertThat(paths(doc))
                .containsExactly(
                        "addresses[0].queues[0].name",
                        "addresses[0].queues[1].routingType",
                        "addresses[0].queues[1].maxConsumers",
                        "addresses[0].queues[1].ringSize",
                        "addresses[0].queues[1].filter",
                        "addresses[0].queues[2].routingType");
    }

    @Test
    void aQueueRoutingTypeIsCaseInsensitiveAndOnlyCheckedAgainstADeclaredAddressSet() {
        // lower-case is accepted; an address that declares no routing types is reported once, not per queue
        BrokerConfigDocument ok =
                addresses(new AddressDecl("a", Set.of("ANYCAST"), List.of(queue("q", "anycast", null, null, null))));
        BrokerConfigDocument noTypes =
                addresses(new AddressDecl("a", Set.of(), List.of(queue("q", "ANYCAST", null, null, null))));

        assertThat(paths(ok)).isEmpty();
        assertThat(paths(noTypes)).containsExactly("addresses[0].routingTypes");
    }

    // ---- address settings --------------------------------------------------

    @Test
    void anAddressSettingNeedsAUniqueMatchAndAtLeastOneKey() {
        BrokerConfigDocument doc = settings(
                new AddressSettingDecl(null, Map.of()),
                new AddressSettingDecl("x.#", Map.of("noExpiry", true)),
                new AddressSettingDecl("x.#", Map.of("noExpiry", true)));

        assertThat(paths(doc))
                .containsExactly("addressSettings[0].match", "addressSettings[0].values", "addressSettings[2].match");
    }

    @Test
    void aNullSettingValueIsRefused() {
        assertThat(paths(settings(new AddressSettingDecl("x", value("noExpiry", null)))))
                .containsExactly("addressSettings[0].values.noExpiry");
    }

    @Test
    void booleanKeysTakeBooleansAndBooleanText() {
        assertThat(paths(settings(new AddressSettingDecl("x", Map.of("noExpiry", true)))))
                .isEmpty();
        assertThat(paths(settings(new AddressSettingDecl("x", Map.of("noExpiry", "TRUE")))))
                .isEmpty();
        assertThat(paths(settings(new AddressSettingDecl("x", Map.of("noExpiry", "yes")))))
                .containsExactly("addressSettings[0].values.noExpiry");
        assertThat(paths(settings(new AddressSettingDecl("x", Map.of("noExpiry", 1)))))
                .containsExactly("addressSettings[0].values.noExpiry");
    }

    @Test
    void wholeNumberKeysRejectFractionsTextAndOutOfRangeValues() {
        // int, may be -1
        assertThat(paths(settings(new AddressSettingDecl("x", Map.of("maxDeliveryAttempts", 1.5)))))
                .containsExactly("addressSettings[0].values.maxDeliveryAttempts");
        assertThat(paths(settings(new AddressSettingDecl("x", Map.of("maxDeliveryAttempts", 2.0)))))
                .isEmpty();
        assertThat(paths(settings(new AddressSettingDecl("x", Map.of("maxDeliveryAttempts", "many")))))
                .containsExactly("addressSettings[0].values.maxDeliveryAttempts");
        assertThat(paths(settings(new AddressSettingDecl("x", Map.of("maxDeliveryAttempts", -2)))))
                .containsExactly("addressSettings[0].values.maxDeliveryAttempts");
        // a key that never takes a negative
        assertThat(paths(settings(new AddressSettingDecl("x", Map.of("pageSizeBytes", -1)))))
                .containsExactly("addressSettings[0].values.pageSizeBytes");
        // 32-bit limit only on int keys
        assertThat(paths(settings(new AddressSettingDecl("x", Map.of("pageSizeBytes", 3_000_000_000L)))))
                .containsExactly("addressSettings[0].values.pageSizeBytes");
        assertThat(paths(settings(new AddressSettingDecl("x", Map.of("maxSizeBytes", 3_000_000_000L)))))
                .isEmpty();
        assertThat(paths(settings(new AddressSettingDecl("x", Map.of("maxSizeBytes", new BigDecimal("10.00"))))))
                .isEmpty();
    }

    @Test
    void decimalKeysAreCheckedForTypeAndRange() {
        assertThat(paths(settings(new AddressSettingDecl("x", Map.of("redeliveryMultiplier", "abc")))))
                .containsExactly("addressSettings[0].values.redeliveryMultiplier");
        assertThat(paths(settings(new AddressSettingDecl("x", Map.of("redeliveryMultiplier", 0)))))
                .containsExactly("addressSettings[0].values.redeliveryMultiplier");
        assertThat(paths(settings(new AddressSettingDecl("x", Map.of("redeliveryMultiplier", 1.5)))))
                .isEmpty();
        assertThat(paths(settings(new AddressSettingDecl("x", Map.of("redeliveryCollisionAvoidanceFactor", 1.5)))))
                .containsExactly("addressSettings[0].values.redeliveryCollisionAvoidanceFactor");
        assertThat(paths(settings(new AddressSettingDecl("x", Map.of("redeliveryCollisionAvoidanceFactor", -0.1)))))
                .containsExactly("addressSettings[0].values.redeliveryCollisionAvoidanceFactor");
        assertThat(paths(settings(new AddressSettingDecl("x", Map.of("redeliveryCollisionAvoidanceFactor", 0.5)))))
                .isEmpty();
    }

    @Test
    void textKeysNeedTextAndEnumKeysNeedAnAllowedValue() {
        assertThat(paths(settings(new AddressSettingDecl("x", Map.of("deadLetterAddress", 5)))))
                .containsExactly("addressSettings[0].values.deadLetterAddress");
        assertThat(paths(settings(new AddressSettingDecl("x", Map.of("deadLetterAddress", "dlq")))))
                .isEmpty();
        assertThat(paths(settings(new AddressSettingDecl("x", Map.of("addressFullMessagePolicy", "page")))))
                .isEmpty();
    }

    @Test
    void numberParsesTheSupportedShapes() {
        assertThat(BrokerConfigValidator.number(null)).isNull();
        assertThat(BrokerConfigValidator.number(new BigDecimal("2"))).isEqualByComparingTo("2");
        assertThat(BrokerConfigValidator.number(7)).isEqualByComparingTo("7");
        assertThat(BrokerConfigValidator.number(" 8 ")).isEqualByComparingTo("8");
        assertThat(BrokerConfigValidator.number("x")).isNull();
        assertThat(BrokerConfigValidator.number(true)).isNull();
    }

    // ---- security settings -------------------------------------------------

    @Test
    void aSecuritySettingNeedsAUniqueMatchPermissionsAndCleanRoles() {
        BrokerConfigDocument doc = security(
                new SecuritySettingDecl(" ", Map.of()),
                new SecuritySettingDecl("x", Map.of(PermissionType.SEND, Set.of("ok"))),
                new SecuritySettingDecl("x", Map.of(PermissionType.SEND, Set.of("${role}"))),
                new SecuritySettingDecl("y", Map.of(PermissionType.SEND, Set.of(" "))));

        assertThat(paths(doc))
                .containsExactly(
                        "securitySettings[0].match",
                        "securitySettings[0].permissions",
                        "securitySettings[2].match",
                        "securitySettings[2].permissions.send",
                        "securitySettings[3].permissions.send");
    }

    // ---- diverts -----------------------------------------------------------

    @Test
    void divertsNeedNameAddressAndForwardingAddress() {
        BrokerConfigDocument doc = diverts(
                new DivertDecl("", "", "", null, false, null, null, null),
                new DivertDecl("d", "a", "b", "x='${p}'", false, "PASS", null, null),
                new DivertDecl("d", "a", "${fwd}", null, false, null, null, null));

        assertThat(paths(doc))
                .containsExactly(
                        "diverts[0].name",
                        "diverts[0].address",
                        "diverts[0].forwardingAddress",
                        "diverts[1].filter",
                        "diverts[2].name",
                        "diverts[2].forwardingAddress");
    }

    // ---- bridges -----------------------------------------------------------

    @Test
    void aValidBridgeHasNoViolations() {
        assertThat(paths(bridges(validBridge()))).isEmpty();
        assertThat(paths(bridges(bridge("b", "q", "fwd", List.of(), "group")))).isEmpty();
    }

    @Test
    void bridgeNamesAreRequiredUniqueAndSafeForAnObjectName() {
        assertThat(paths(bridges(bridge(" ", "q", "f", List.of("c"), null)))).containsExactly("bridges[0].name");
        assertThat(paths(bridges(validBridge(), validBridge()))).containsExactly("bridges[1].name");
        assertThat(paths(bridges(bridge("has space", "q", "f", List.of("c"), null))))
                .containsExactly("bridges[0].name");
    }

    @Test
    void aBridgeNeedsItsQueueAndForwardingAddress() {
        assertThat(paths(bridges(bridge("b", "", "", List.of("c"), null))))
                .containsExactly("bridges[0].queueName", "bridges[0].forwardingAddress");
    }

    @Test
    void aBridgeUsesExactlyOneConnectionSource() {
        assertThat(paths(bridges(bridge("b", "q", "f", List.of("c"), "group"))))
                .containsExactly("bridges[0].staticConnectors");
        assertThat(paths(bridges(bridge("b", "q", "f", List.of(), null))))
                .containsExactly("bridges[0].staticConnectors");
    }

    @Test
    void aBridgeRoutingTypeMustBeOneTheBrokerKnows() {
        BridgeDecl bad = new BridgeDecl(
                "b",
                "q",
                "f",
                null,
                null,
                List.of("c"),
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
                "SIDEWAYS",
                null,
                null,
                null);
        BridgeDecl offset = new BridgeDecl(
                "b",
                "q",
                "f",
                null,
                null,
                List.of("c"),
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
                "OFFSET",
                null,
                null,
                null);

        assertThat(paths(bridges(bad))).containsExactly("bridges[0].routingType");
        assertThat(paths(bridges(offset))).isEmpty();
    }

    @Test
    void aBridgeTransformerNeedsAClassAndPlaceholdersAreRefused() {
        BridgeDecl b = new BridgeDecl(
                "b",
                "${q}",
                "${f}",
                "x='${v}'",
                new TransformerDecl(null, Map.of("k", "v")),
                List.of("${c}"),
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

        // TransformerDecl.of would drop a class-less transformer, but the record itself keeps it.
        assertThat(paths(bridges(b)))
                .containsExactly(
                        "bridges[0].transformer.className",
                        "bridges[0].filter",
                        "bridges[0].queueName",
                        "bridges[0].forwardingAddress",
                        "bridges[0].staticConnectors");
    }

    @Test
    void bridgeLimitsAreRefusedBelowTheirFloor() {
        BridgeDecl below = new BridgeDecl(
                "b",
                "q",
                "f",
                null,
                null,
                List.of("c"),
                null,
                null,
                null,
                0L, // retryInterval
                0.0, // retryIntervalMultiplier
                0L, // maxRetryInterval
                -2, // initialConnectAttempts
                -2, // reconnectAttempts
                -2, // confirmationWindowSize
                -2, // producerWindowSize
                0, // minLargeMessageSize
                0L, // checkPeriod
                -2L, // connectionTtl
                null,
                0, // concurrency
                null,
                null);

        List<String> expected = new ArrayList<>(List.of(
                "bridges[0].retryInterval",
                "bridges[0].maxRetryInterval",
                "bridges[0].checkPeriod",
                "bridges[0].connectionTtl",
                "bridges[0].confirmationWindowSize",
                "bridges[0].producerWindowSize",
                "bridges[0].minLargeMessageSize",
                "bridges[0].initialConnectAttempts",
                "bridges[0].reconnectAttempts",
                "bridges[0].concurrency",
                "bridges[0].retryIntervalMultiplier"));

        assertThat(paths(bridges(below))).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void bridgeLimitsAtTheirFloorAreAccepted() {
        BridgeDecl atFloor = new BridgeDecl(
                "b",
                "q",
                "f",
                null,
                null,
                List.of("c"),
                null,
                null,
                null,
                1L,
                0.1,
                1L,
                -1,
                -1,
                -1,
                -1,
                1,
                1L,
                -1L,
                null,
                1,
                null,
                null);

        assertThat(paths(bridges(atFloor))).isEmpty();
    }
}
