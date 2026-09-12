package io.github.sudoitir.artemisstudio.domain.brokerconfig;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.domain.brokerconfig.BrokerConfigDocument.AddressSettingDecl;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.BrokerXmlCodec.ParseResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * XML is an interchange format (ADR-0067 D1): a full file parses, everything the
 * management API cannot apply is listed by path rather than dropped, placeholders are
 * refused by name, and what is written reads back as the same document.
 */
class BrokerXmlCodecTest {

    private static String fixture() throws IOException {
        try (var in = BrokerXmlCodecTest.class.getResourceAsStream("/brokerconfig/fragment.xml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void parsesTheFourSectionsFromAFullBrokerXml() throws IOException {
        ParseResult result = BrokerXmlCodec.parse(fixture());
        BrokerConfigDocument doc = result.document();

        assertThat(doc.addresses()).extracting(a -> a.name()).containsExactly("DLQ", "orders.request.v1");
        assertThat(doc.addresses().get(1).queues().getFirst().filter()).isEqualTo("region = 'eu'");
        assertThat(doc.addresses().get(1).queues().getFirst().maxConsumers()).isEqualTo(4);

        AddressSettingDecl orders = doc.addressSettings().getFirst();
        assertThat(orders.match()).isEqualTo("orders.#");
        assertThat(orders.values())
                .containsEntry("addressFullMessagePolicy", "PAGE")
                .containsEntry("maxSizeBytes", 104857600L)
                .containsEntry("redeliveryMultiplier", 2.0)
                .containsEntry("autoCreateQueues", false)
                .containsEntry("pageFullMessagePolicy", "FAIL")
                .doesNotContainKey("not-a-real-key");

        assertThat(doc.securitySettings()).hasSize(1);
        assertThat(doc.securitySettings().getFirst().roles(PermissionType.SEND))
                .containsExactly("app-role", "ops-role");

        assertThat(doc.diverts()).hasSize(1);
        assertThat(doc.diverts().getFirst().filter()).isEqualTo("amount > 100");
        assertThat(doc.diverts().getFirst().exclusive()).isFalse();
    }

    @Test
    void listsEverythingItCannotApplyByPathAndDropsNothingSilently() throws IOException {
        ParseResult result = BrokerXmlCodec.parse(fixture());

        assertThat(result.unsupported())
                .extracting(BrokerXmlCodec.Unsupported::path)
                .contains(
                        "configuration/core/name",
                        "configuration/core/persist-delivery-count-before-delivery",
                        "configuration/core/ha-policy",
                        "configuration/core/address-settings/address-setting[match=orders.reply.#]/config-delete-queues",
                        "configuration/core/security-settings/security-setting-plugin");
        // An element Studio cannot apply is listed; a key that is meant to be an
        // address setting and is not one is an error, because the broker would take it
        // and do nothing (ADR-0067 D10).
        assertThat(result.errors())
                .extracting(Violation::path)
                .contains("configuration/core/address-settings/address-setting[match=orders.#]/not-a-real-key");
        // global-max-size is unsupported AND a placeholder; it is listed as unsupported
        // (the element is skipped before its value is read), which is the honest answer.
        assertThat(result.unsupported()).anyMatch(u -> u.path().endsWith("/global-max-size"));
    }

    @Test
    void refusesAPlaceholderByName() {
        ParseResult result = BrokerXmlCodec.parse("""
                <address-settings>
                  <address-setting match="orders.#">
                    <page-limit-bytes>${PAGE_LIMIT}</page-limit-bytes>
                  </address-setting>
                </address-settings>
                """);
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().getFirst().path()).endsWith("[match=orders.#]/page-limit-bytes");
        assertThat(result.errors().getFirst().message()).contains("placeholder");
        assertThat(result.document().addressSettings().getFirst().values()).doesNotContainKey("pageLimitBytes");
    }

    @Test
    void acceptsBareItemsAsACapabilitySnippetWritesThem() {
        // The ledger's snippets carry items without their <…-settings> wrapper, next to
        // a static block; the items are carried and the block is listed, never dropped.
        ParseResult result = BrokerXmlCodec.parse("""
                <address-settings>
                  <address-setting match="#">
                    <slow-consumer-threshold>1</slow-consumer-threshold>
                  </address-setting>
                </address-settings>

                <broker-plugins>
                  <broker-plugin class-name="org.example.Plugin"/>
                </broker-plugins>

                <security-setting match="activemq.notifications">
                  <permission type="consume" roles="amq"/>
                </security-setting>
                """);
        assertThat(result.errors()).isEmpty();
        assertThat(result.document().addressSettings()).hasSize(1);
        assertThat(result.document().securitySettings()).hasSize(1);
        assertThat(result.document().securitySettings().getFirst().match()).isEqualTo("activemq.notifications");
        assertThat(result.document().securitySettings().getFirst().permissions())
                .containsEntry(PermissionType.CONSUME, Set.of("amq"));
        assertThat(result.unsupported())
                .extracting(BrokerXmlCodec.Unsupported::path)
                .containsExactly("broker-plugins");
    }

    @Test
    void reportsMalformedXmlWithItsPosition() {
        ParseResult result = BrokerXmlCodec.parse("<address-settings><address-setting match='x'>");
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().getFirst().message()).startsWith("Not well-formed XML");
    }

    @Test
    void writeThenParseIsLossless() throws IOException {
        BrokerConfigDocument original = BrokerXmlCodec.parse(fixture()).document();

        String xml = BrokerXmlCodec.write(original);
        ParseResult back = BrokerXmlCodec.parse(xml);

        assertThat(back.errors()).isEmpty();
        assertThat(back.unsupported()).isEmpty();
        assertThat(back.document()).isEqualTo(original);
        assertThat(xml).contains("<filter string=\"amount &gt; 100\"/>").contains("roles=\"app-role,ops-role\"");
    }

    @Test
    void escapesAttributeValues() {
        BrokerConfigDocument doc = new BrokerConfigDocument(
                1,
                List.of(),
                List.of(),
                List.of(),
                List.of(new BrokerConfigDocument.DivertDecl(
                        "d", "a", "b", "name = \"x\" AND size < 5", true, null, null, Map.of())));
        String xml = BrokerXmlCodec.write(doc);
        assertThat(xml).contains("string=\"name = &quot;x&quot; AND size &lt; 5\"");
        assertThat(BrokerXmlCodec.parse(xml).document().diverts().getFirst().filter())
                .isEqualTo("name = \"x\" AND size < 5");
    }

    @Test
    void anUnknownAddressSettingKeyIsRefusedRatherThanDropped() {
        // A broker takes an unknown key and ignores it (§15 M1), so a typo that was
        // carried as merely "unsupported" produced a declaration that looked applied,
        // could never drift, and did nothing. ADR-0067 D10: it is a validation error.
        ParseResult result = BrokerXmlCodec.parse("""
                <address-settings>
                  <address-setting match="#">
                    <max-size-byte>10</max-size-byte>
                  </address-setting>
                </address-settings>
                """);
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().getFirst().message()).contains("max-size-byte", "silently ignores");
        assertThat(result.unsupported()).isEmpty();
    }

    @Test
    void anImportLargerThanTheCapIsRefusedBeforeItIsParsed() {
        String huge = "<address-settings>"
                + "<address-setting match=\"a.#\"><max-delivery-attempts>1</max-delivery-attempts></address-setting>"
                        .repeat(4000)
                + "</address-settings>";
        ParseResult result = BrokerXmlCodec.parse(huge);
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().getFirst().message()).contains("KiB");
        assertThat(result.document().addressSettings()).isEmpty();
    }

    @Test
    void anExternalEntityIsNotResolved() {
        ParseResult result = BrokerXmlCodec.parse("""
                <?xml version="1.0"?>
                <!DOCTYPE x [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <address-settings><address-setting match="&xxe;"/></address-settings>
                """);
        // Whatever it does with the DOCTYPE, it must not read the file.
        assertThat(result.document().addressSettings())
                .allSatisfy(s -> assertThat(s.match()).doesNotContain("root:"));
    }

    @Test
    void aBareFragmentOfSeveralSectionsParses() {
        ParseResult result = BrokerXmlCodec.parse("""
                <addresses><address name="a"><multicast><queue name="q"/></multicast></address></addresses>
                <diverts/>
                """);
        assertThat(result.errors()).isEmpty();
        assertThat(result.document().addresses().getFirst().routingTypes()).isEqualTo(Set.of("MULTICAST"));
    }
}
