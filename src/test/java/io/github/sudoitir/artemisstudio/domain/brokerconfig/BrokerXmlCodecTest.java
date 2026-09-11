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
                        "configuration/core/address-settings/address-setting[match=orders.#]/not-a-real-key",
                        "configuration/core/address-settings/address-setting[match=orders.reply.#]/config-delete-queues",
                        "configuration/core/security-settings/security-setting-plugin");
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
    void aBareFragmentOfSeveralSectionsParses() {
        ParseResult result = BrokerXmlCodec.parse("""
                <addresses><address name="a"><multicast><queue name="q"/></multicast></address></addresses>
                <diverts/>
                """);
        assertThat(result.errors()).isEmpty();
        assertThat(result.document().addresses().getFirst().routingTypes()).isEqualTo(Set.of("MULTICAST"));
    }
}
