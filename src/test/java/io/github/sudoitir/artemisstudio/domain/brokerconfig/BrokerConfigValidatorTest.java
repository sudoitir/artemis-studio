package io.github.sudoitir.artemisstudio.domain.brokerconfig;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.domain.brokerconfig.BrokerConfigDocument.AddressDecl;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.BrokerConfigDocument.AddressSettingDecl;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.BrokerConfigDocument.DivertDecl;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.BrokerConfigDocument.QueueDecl;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.BrokerConfigDocument.SecuritySettingDecl;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Validation is strict because the broker is not: an unknown key is accepted and ignored (§15 M1). */
class BrokerConfigValidatorTest {

    private static BrokerConfigDocument withSettings(AddressSettingDecl... settings) {
        return new BrokerConfigDocument(1, List.of(), List.of(settings), List.of(), List.of());
    }

    @Test
    void rejectsAnUnknownKeyNamingItsPath() {
        List<Violation> v = BrokerConfigValidator.validate(
                withSettings(new AddressSettingDecl("orders.#", Map.of("maxSizeByte", 10))));
        assertThat(v).hasSize(1);
        assertThat(v.getFirst().path()).isEqualTo("addressSettings[0].values.maxSizeByte");
        assertThat(v.getFirst().message()).contains("silently ignore");
    }

    @Test
    void rejectsConfigDeleteKeysAndBadEnums() {
        List<Violation> v = BrokerConfigValidator.validate(withSettings(new AddressSettingDecl(
                "orders.#", Map.of("configDeleteQueues", "FORCE", "addressFullMessagePolicy", "BOGUS"))));
        assertThat(v)
                .extracting(Violation::path)
                .containsExactlyInAnyOrder(
                        "addressSettings[0].values.configDeleteQueues",
                        "addressSettings[0].values.addressFullMessagePolicy");
    }

    @Test
    void rejectsPageSizeNotBelowMaxSizeWhenBothDeclared() {
        List<Violation> v = BrokerConfigValidator.validate(
                withSettings(new AddressSettingDecl("orders.#", Map.of("maxSizeBytes", 1000, "pageSizeBytes", 4096))));
        assertThat(v).hasSize(1);
        assertThat(v.getFirst().message()).contains("page-size-bytes must be lower");
    }

    @Test
    void acceptsTextNumbersAndRejectsPlaceholders() {
        assertThat(BrokerConfigValidator.validate(
                        withSettings(new AddressSettingDecl("orders.#", Map.of("maxSizeBytes", "104857600")))))
                .isEmpty();
        List<Violation> v = BrokerConfigValidator.validate(
                withSettings(new AddressSettingDecl("orders.#", Map.of("deadLetterAddress", "${DLQ}"))));
        assertThat(v).hasSize(1);
        assertThat(v.getFirst().message()).contains("placeholder");
    }

    @Test
    void queuesMustMatchTheirAddressRoutingAndBeUnique() {
        BrokerConfigDocument doc = new BrokerConfigDocument(
                1,
                List.of(
                        new AddressDecl(
                                "a",
                                Set.of("ANYCAST"),
                                List.of(new QueueDecl("q", "MULTICAST", null, true, null, null, null, null, null))),
                        new AddressDecl(
                                "b",
                                Set.of("ANYCAST"),
                                List.of(new QueueDecl("q", "ANYCAST", null, true, null, null, null, null, null)))),
                List.of(),
                List.of(),
                List.of());
        List<Violation> v = BrokerConfigValidator.validate(doc);
        assertThat(v)
                .extracting(Violation::path)
                .containsExactlyInAnyOrder("addresses[0].queues[0].routingType", "addresses[1].queues[0].name");
    }

    @Test
    void securityRolesAreSingleWordsAndDivertsNeedBothEnds() {
        BrokerConfigDocument doc = new BrokerConfigDocument(
                1,
                List.of(),
                List.of(),
                List.of(new SecuritySettingDecl("x.#", Map.of(PermissionType.SEND, Set.of("a,b")))),
                List.of(new DivertDecl("d", "same", "same", null, false, "SIDEWAYS", null, Map.of())));
        List<Violation> v = BrokerConfigValidator.validate(doc);
        assertThat(v)
                .extracting(Violation::path)
                .containsExactlyInAnyOrder(
                        "securitySettings[0].permissions.send",
                        "diverts[0].forwardingAddress",
                        "diverts[0].routingType");
    }
}
