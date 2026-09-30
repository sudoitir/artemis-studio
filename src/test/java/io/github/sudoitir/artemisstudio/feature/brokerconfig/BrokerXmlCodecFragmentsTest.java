package io.github.sudoitir.artemisstudio.feature.brokerconfig;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.feature.brokerconfig.BrokerConfigDocument.AddressDecl;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.BrokerConfigDocument.BridgeDecl;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.BrokerConfigDocument.DivertDecl;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.BrokerConfigDocument.QueueDecl;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.BrokerConfigDocument.SecuritySettingDecl;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.BrokerXmlCodec.ParseResult;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.BrokerXmlCodec.Unsupported;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Small fragments, one behaviour each: the input guards, the bare items a pasted
 * fragment carries, every element the parser refuses or reports, and the writer's
 * optional branches.
 */
class BrokerXmlCodecFragmentsTest {

    private static List<String> unsupportedPaths(ParseResult r) {
        return r.unsupported().stream().map(Unsupported::path).toList();
    }

    private static List<String> errorPaths(ParseResult r) {
        return r.errors().stream().map(Violation::path).toList();
    }

    // ---- input guards ------------------------------------------------------

    @Test
    void blankInputIsNothingToImport() {
        for (String xml : new String[] {null, "", "   "}) {
            ParseResult r = BrokerXmlCodec.parse(xml);

            assertThat(r.errors()).extracting(Violation::message).containsExactly("Nothing to import.");
            assertThat(r.document().isEmpty()).isTrue();
        }
    }

    @Test
    void anOversizedImportIsRefusedBeforeParsing() {
        ParseResult r = BrokerXmlCodec.parse("<core>" + "x".repeat(BrokerXmlCodec.MAX_IMPORT_CHARS) + "</core>");

        assertThat(r.errors()).hasSize(1);
        assertThat(r.errors().getFirst().message()).contains("KiB").contains("largest import");
        assertThat(r.document().isEmpty()).isTrue();
    }

    @Test
    void malformedXmlIsReportedWithItsLocation() {
        ParseResult r = BrokerXmlCodec.parse("<core><addresses></core>");

        assertThat(r.errors()).hasSize(1);
        assertThat(r.errors().getFirst().message())
                .startsWith("Not well-formed XML")
                .contains("line");
    }

    @Test
    void aPrologWithBomDeclarationAndDoctypeIsDropped() {
        String xml = "﻿<?xml version=\"1.0\"?>\n<!DOCTYPE configuration [<!ENTITY x \"y\">]>\n"
                + "<configuration><core><addresses><address name=\"a\"><anycast/></address></addresses></core>"
                + "</configuration>";

        ParseResult r = BrokerXmlCodec.parse(xml);

        assertThat(r.errors()).isEmpty();
        assertThat(r.document().addresses()).extracting(AddressDecl::name).containsExactly("a");
    }

    // ---- bare items and unexpected structure -------------------------------

    @Test
    void bareItemsAsAFragmentAreTaken() {
        ParseResult r = BrokerXmlCodec.parse("""
                <address name="a"><multicast><queue name="q"/></multicast></address>
                <address-setting match="x.#"><no-expiry>true</no-expiry></address-setting>
                <security-setting match="x.#"><permission type="send" roles="r1, r2"/></security-setting>
                <divert name="d"><address>a</address><forwarding-address>b</forwarding-address></divert>
                <bridge name="br"><queue-name>q</queue-name><forwarding-address>f</forwarding-address></bridge>
                """);

        BrokerConfigDocument doc = r.document();
        assertThat(r.errors()).isEmpty();
        assertThat(doc.addresses().getFirst().routingTypes()).containsExactly("MULTICAST");
        assertThat(doc.addresses().getFirst().queues().getFirst().routingType()).isEqualTo("MULTICAST");
        assertThat(doc.addressSettings().getFirst().values()).containsEntry("noExpiry", true);
        assertThat(doc.securitySettings().getFirst().roles(PermissionType.SEND)).containsExactly("r1", "r2");
        assertThat(doc.diverts().getFirst().forwardingAddress()).isEqualTo("b");
        assertThat(doc.bridges().getFirst().queueName()).isEqualTo("q");
    }

    @Test
    void topLevelElementsAreExplainedByWhereTheyBelong() {
        ParseResult r = BrokerXmlCodec.parse("""
                <queue name="q"/>
                <queues/>
                <anycast/>
                <multicast/>
                <permission type="send" roles="x"/>
                <max-size-bytes>1</max-size-bytes>
                <something-else/>
                """);

        assertThat(r.unsupported())
                .anyMatch(u -> u.path().equals("queue") && u.reason().contains("A queue belongs"))
                .anyMatch(u -> u.path().equals("queues") && u.reason().contains("A queue belongs"))
                .anyMatch(u -> u.path().equals("anycast") && u.reason().contains("routing type belongs"))
                .anyMatch(u -> u.path().equals("multicast") && u.reason().contains("routing type belongs"))
                .anyMatch(u -> u.path().equals("permission") && u.reason().contains("security setting"))
                .anyMatch(u -> u.path().equals("max-size-bytes") && u.reason().contains("address-setting"))
                .anyMatch(u -> u.path().equals("something-else") && u.reason().contains("management API"));
    }

    @Test
    void unexpectedChildrenOfEachSectionAreListedAndSkipped() {
        ParseResult r = BrokerXmlCodec.parse("""
                <addresses><nope/></addresses>
                <address-settings><nope/></address-settings>
                <security-settings><nope/></security-settings>
                <diverts><nope/></diverts>
                <bridges><nope/></bridges>
                <address name="a">
                  <weird/>
                  <anycast><nope/><queue name="q"/></anycast>
                </address>
                <security-setting match="m"><nope/></security-setting>
                """);

        assertThat(unsupportedPaths(r))
                .contains(
                        "addresses/nope",
                        "address-settings/nope",
                        "security-settings/nope",
                        "diverts/nope",
                        "bridges/nope",
                        "address[name=a]/weird",
                        "address[name=a]/anycast/nope",
                        "security-setting[match=m]/nope");
        assertThat(r.document().addresses().getFirst().queues())
                .extracting(QueueDecl::name)
                .containsExactly("q");
    }

    // ---- queues ------------------------------------------------------------

    @Test
    void queueChildrenAreCoercedAndBadOnesAreReportedByPath() {
        ParseResult r = BrokerXmlCodec.parse("""
                <address name="a"><anycast>
                  <queue name="q">
                    <filter string="x = 1"/>
                    <durable>false</durable>
                    <max-consumers>3</max-consumers>
                    <purge-on-no-consumers>true</purge-on-no-consumers>
                    <exclusive>true</exclusive>
                    <non-destructive>true</non-destructive>
                    <ring-size>5</ring-size>
                    <unknown>1</unknown>
                  </queue>
                  <queue name="bad"><max-consumers>lots</max-consumers><ring-size>${r}</ring-size></queue>
                </anycast></address>
                """);

        QueueDecl q = r.document().addresses().getFirst().queues().getFirst();
        assertThat(q.filter()).isEqualTo("x = 1");
        assertThat(q.durable()).isFalse();
        assertThat(q.maxConsumers()).isEqualTo(3);
        assertThat(q.purgeOnNoConsumers()).isTrue();
        assertThat(q.exclusive()).isTrue();
        assertThat(q.nonDestructive()).isTrue();
        assertThat(q.ringSize()).isEqualTo(5L);
        assertThat(unsupportedPaths(r)).containsExactly("address[name=a]/anycast/queue[name=q]/unknown");
        // a placeholder is reported as one, and then as the non-number it also is
        assertThat(errorPaths(r)).hasSize(3);
        assertThat(r.errors().getFirst().message()).contains("'lots' is not a number");
        assertThat(r.errors().get(1).message()).contains("placeholder");
    }

    @Test
    void aPlaceholderInAFilterOrAddressNameIsRefused() {
        ParseResult r = BrokerXmlCodec.parse("""
                <address name="${a}"><anycast><queue name="q"><filter string="${f}"/></queue></anycast></address>
                """);

        assertThat(r.errors()).hasSize(2).allMatch(v -> v.message().contains("placeholder"));
    }

    // ---- address settings --------------------------------------------------

    @Test
    void addressSettingValuesAreCoercedByType() {
        ParseResult r = BrokerXmlCodec.parse("""
                <address-setting match="m">
                  <max-size-bytes>10</max-size-bytes>
                  <redelivery-delay-multiplier>1.5</redelivery-delay-multiplier>
                  <auto-create-queues>maybe</auto-create-queues>
                  <max-delivery-attempts>abc</max-delivery-attempts>
                  <redistribution-delay>${d}</redistribution-delay>
                  <dead-letter-address> dlq </dead-letter-address>
                </address-setting>
                """);

        assertThat(r.document().addressSettings().getFirst().values())
                .containsEntry("maxSizeBytes", 10L)
                .containsEntry("redeliveryMultiplier", 1.5)
                .containsEntry("deadLetterAddress", "dlq")
                .containsEntry("maxDeliveryAttempts", "abc")
                .doesNotContainKey("redistributionDelay");
        assertThat(r.errors())
                .extracting(Violation::message)
                .anyMatch(m -> m.contains("'maybe' is not true or false"))
                .anyMatch(m -> m.contains("'abc' is not a number"))
                .anyMatch(m -> m.contains("placeholder"));
    }

    @Test
    void anUnknownAddressSettingKeyIsAnErrorNotADrop() {
        ParseResult r = BrokerXmlCodec.parse(
                "<address-setting match=\"m\"><max-size-byte>1</max-size-byte>" + "</address-setting>");

        assertThat(errorPaths(r)).containsExactly("address-setting[match=m]/max-size-byte");
    }

    @Test
    void aReloadOnlyKeyIsListedAsUnsupported() {
        ParseResult r = BrokerXmlCodec.parse(
                "<address-setting match=\"m\"><config-delete-queues>FORCE</config-delete-queues></address-setting>");

        assertThat(unsupportedPaths(r)).containsExactly("address-setting[match=m]/config-delete-queues");
        assertThat(r.document().addressSettings().getFirst().values()).isEmpty();
    }

    // ---- security settings -------------------------------------------------

    @Test
    void permissionsWithUnknownTypesMissingRolesAndPlaceholdersAreHandled() {
        ParseResult r = BrokerXmlCodec.parse("""
                <security-setting match="m">
                  <permission type="bogus" roles="x"/>
                  <permission type="send"/>
                  <permission type="consume" roles="${r}"/>
                  <permission type="browse" roles=" a , ,b "/>
                </security-setting>
                """);

        SecuritySettingDecl s = r.document().securitySettings().getFirst();
        assertThat(s.roles(PermissionType.BROWSE)).containsExactly("a", "b");
        assertThat(s.permissions()).containsOnlyKeys(PermissionType.BROWSE);
        assertThat(unsupportedPaths(r)).containsExactly("security-setting[match=m]/permission[type=bogus]");
        assertThat(errorPaths(r)).hasSize(1);
    }

    // ---- diverts -----------------------------------------------------------

    @Test
    void divertChildrenAreCarriedOrReportedByKind() {
        ParseResult r = BrokerXmlCodec.parse("""
                <divert name="d">
                  <routing-name>d</routing-name>
                  <address>a</address>
                  <forwarding-address>${f}</forwarding-address>
                  <exclusive>true</exclusive>
                  <routing-type>ANYCAST</routing-type>
                  <filter string="x = 1"/>
                  <transformer>
                    <class-name>com.example.T</class-name>
                    <property key="k" value="v"/>
                    <other/>
                  </transformer>
                  <mystery/>
                </divert>
                <divert name="e">
                  <routing-name>renamed</routing-name>
                  <routing-name></routing-name>
                  <transformer><property key="k" value="v"/></transformer>
                </divert>
                """);

        DivertDecl d = r.document().diverts().getFirst();
        assertThat(d.address()).isEqualTo("a");
        assertThat(d.exclusive()).isTrue();
        assertThat(d.routingType()).isEqualTo("ANYCAST");
        assertThat(d.filter()).isEqualTo("x = 1");
        assertThat(d.transformerClassName()).isEqualTo("com.example.T");
        assertThat(d.transformerProperties()).containsEntry("k", "v");
        // a transformer without a class configures nothing
        DivertDecl e = r.document().diverts().get(1);
        assertThat(e.transformerClassName()).isNull();
        assertThat(e.transformerProperties()).isEmpty();
        assertThat(unsupportedPaths(r))
                .contains(
                        "diverts[name=d]/transformer/other".replace("diverts", "divert"),
                        "divert[name=d]/mystery",
                        "divert[name=e]/routing-name");
        assertThat(errorPaths(r)).containsExactly("divert[name=d]/forwarding-address");
    }

    @Test
    void textIgnoresNestedElementsAndKeepsOnlyTheElementsOwnCharacters() {
        ParseResult r = BrokerXmlCodec.parse(
                "<divert name=\"d\"><address>a<junk>zzz</junk>b</address><forwarding-address>f</forwarding-address></divert>");

        assertThat(r.document().diverts().getFirst().address()).isEqualTo("ab");
    }

    // ---- bridges -----------------------------------------------------------

    @Test
    void aBridgeCarriesEveryFieldAndNamesTheCredentialItDoesNotCarry() {
        ParseResult r = BrokerXmlCodec.parse("""
                <bridge name="b">
                  <queue-name>q</queue-name>
                  <forwarding-address>f</forwarding-address>
                  <filter string="a = 1"/>
                  <transformer><class-name>com.example.T</class-name></transformer>
                  <ha>true</ha>
                  <use-duplicate-detection>false</use-duplicate-detection>
                  <retry-interval>10</retry-interval>
                  <retry-interval-multiplier>1.5</retry-interval-multiplier>
                  <max-retry-interval>100</max-retry-interval>
                  <initial-connect-attempts>-1</initial-connect-attempts>
                  <reconnect-attempts>-1</reconnect-attempts>
                  <confirmation-window-size>1</confirmation-window-size>
                  <producer-window-size>2</producer-window-size>
                  <min-large-message-size>3</min-large-message-size>
                  <check-period>4</check-period>
                  <connection-ttl>5</connection-ttl>
                  <routing-type>PASS</routing-type>
                  <concurrency>2</concurrency>
                  <client-id>cid</client-id>
                  <user>u</user>
                  <password>p</password>
                  <static-connectors><connector-ref>c1</connector-ref><other/></static-connectors>
                  <mystery/>
                </bridge>
                """);

        BridgeDecl b = r.document().bridges().getFirst();
        assertThat(b.filter()).isEqualTo("a = 1");
        assertThat(b.transformer().className()).isEqualTo("com.example.T");
        assertThat(b.ha()).isTrue();
        assertThat(b.useDuplicateDetection()).isFalse();
        assertThat(b.retryInterval()).isEqualTo(10L);
        assertThat(b.retryIntervalMultiplier()).isEqualTo(1.5);
        assertThat(b.maxRetryInterval()).isEqualTo(100L);
        assertThat(b.initialConnectAttempts()).isEqualTo(-1);
        assertThat(b.reconnectAttempts()).isEqualTo(-1);
        assertThat(b.confirmationWindowSize()).isEqualTo(1);
        assertThat(b.producerWindowSize()).isEqualTo(2);
        assertThat(b.minLargeMessageSize()).isEqualTo(3);
        assertThat(b.checkPeriod()).isEqualTo(4L);
        assertThat(b.connectionTtl()).isEqualTo(5L);
        assertThat(b.routingType()).isEqualTo("PASS");
        assertThat(b.concurrency()).isEqualTo(2);
        assertThat(b.clientId()).isEqualTo("cid");
        assertThat(b.credentialRef()).isNull();
        assertThat(b.staticConnectors()).containsExactly("c1");
        assertThat(unsupportedPaths(r))
                .containsExactlyInAnyOrder(
                        "bridge[name=b]/user",
                        "bridge[name=b]/password",
                        "bridge[name=b]/static-connectors/other",
                        "bridge[name=b]/mystery");
        assertThat(r.errors()).isEmpty();
    }

    @Test
    void aBridgeDiscoveryGroupIsReadFromEitherSpelling() {
        ParseResult ref =
                BrokerXmlCodec.parse("<bridge name=\"b\"><discovery-group-ref discovery-group-name=\"g\"/></bridge>");
        ParseResult text =
                BrokerXmlCodec.parse("<bridge name=\"b\"><discovery-group-name>g2</discovery-group-name></bridge>");

        assertThat(ref.document().bridges().getFirst().discoveryGroupName()).isEqualTo("g");
        assertThat(text.document().bridges().getFirst().discoveryGroupName()).isEqualTo("g2");
    }

    @Test
    void bridgeValuesThatCannotBeReadAreReportedAndLeftUnset() {
        ParseResult r = BrokerXmlCodec.parse("""
                <bridge name="${n}">
                  <queue-name>${q}</queue-name>
                  <ha>maybe</ha>
                  <use-duplicate-detection>${x}</use-duplicate-detection>
                  <retry-interval>soon</retry-interval>
                  <check-period>${p}</check-period>
                  <retry-interval-multiplier>big</retry-interval-multiplier>
                  <connection-ttl>${t}</connection-ttl>
                  <concurrency>99999999999</concurrency>
                  <producer-window-size>-99999999999</producer-window-size>
                  <reconnect-attempts>x</reconnect-attempts>
                  <retry-interval-multiplier>${m}</retry-interval-multiplier>
                </bridge>
                """);

        BridgeDecl b = r.document().bridges().getFirst();
        assertThat(b.ha()).isNull();
        assertThat(b.useDuplicateDetection()).isNull();
        assertThat(b.retryInterval()).isNull();
        assertThat(b.checkPeriod()).isNull();
        assertThat(b.retryIntervalMultiplier()).isNull();
        assertThat(b.concurrency()).isNull();
        assertThat(b.producerWindowSize()).isNull();
        assertThat(b.reconnectAttempts()).isNull();
        assertThat(r.errors())
                .extracting(Violation::message)
                .anyMatch(m -> m.contains("'maybe' is not true or false"))
                .anyMatch(m -> m.contains("'soon' is not a number"))
                .anyMatch(m -> m.contains("'big' is not a number"))
                .anyMatch(m -> m.contains("99999999999 is outside the broker's 32-bit range"))
                .anyMatch(m -> m.contains("-99999999999 is outside the broker's 32-bit range"));
        assertThat(r.errors().stream().filter(v -> v.message().contains("placeholder")))
                .hasSize(6);
    }

    // ---- write -------------------------------------------------------------

    private static BrokerConfigDocument doc(
            List<AddressDecl> addresses,
            List<SecuritySettingDecl> security,
            List<DivertDecl> diverts,
            List<BridgeDecl> bridges) {
        return new BrokerConfigDocument(1, addresses, List.of(), security, diverts, bridges);
    }

    @Test
    void anEmptyDocumentWritesOnlyTheHeaderComment() {
        String xml = BrokerXmlCodec.write(BrokerConfigDocument.empty());

        assertThat(xml).isEqualTo("<!-- Generated by Artemis Studio. Paste inside <core> of broker.xml. -->\n");
    }

    @Test
    void queuesWriteSimplyOrWithOnlyTheirDeclaredFields() {
        var simple = new QueueDecl("plain", "ANYCAST", null, true, null, null, null, null, null);
        var full = new QueueDecl("full", "ANYCAST", "x = 1", false, 2, true, false, true, 4L);

        String xml = BrokerXmlCodec.write(doc(
                List.of(new AddressDecl("a", Set.of("ANYCAST"), List.of(simple, full))),
                List.of(),
                List.of(),
                List.of()));

        assertThat(xml)
                .contains("<queue name=\"plain\"/>")
                .contains("<queue name=\"full\">")
                .contains("<filter string=\"x = 1\"/>")
                .contains("<durable>false</durable>")
                .contains("<max-consumers>2</max-consumers>")
                .contains("<purge-on-no-consumers>true</purge-on-no-consumers>")
                .contains("<exclusive>false</exclusive>")
                .contains("<non-destructive>true</non-destructive>")
                .contains("<ring-size>4</ring-size>");
        assertThat(BrokerXmlCodec.parse(xml).document().addresses().getFirst().queues())
                .containsExactly(simple, full);
    }

    @Test
    void securitySettingsAndDivertsWriteTheirOptionalPartsOnlyWhenDeclared() {
        var security = new SecuritySettingDecl(
                "m", Map.of(PermissionType.SEND, Set.of("b", "a"), PermissionType.BROWSE, Set.of()));
        var bare = new DivertDecl("d1", "a", "b", null, false, null, null, null);
        var full = new DivertDecl("d2", "a", "b", "x = 1", true, "STRIP", "com.example.T", Map.of("k", "v"));

        String xml = BrokerXmlCodec.write(doc(List.of(), List.of(security), List.of(bare, full), List.of()));

        assertThat(xml).contains("<permission type=\"send\" roles=\"a,b\"/>").doesNotContain("browse");
        assertThat(xml).contains("<filter string=\"x = 1\"/>").contains("<routing-type>STRIP</routing-type>");
        assertThat(xml)
                .contains("<class-name>com.example.T</class-name>")
                .contains("<property key=\"k\" value=\"v\"/>");
        ParseResult back = BrokerXmlCodec.parse(xml);
        assertThat(back.errors()).isEmpty();
        assertThat(back.document().diverts()).containsExactly(bare, full);
        assertThat(back.document().securitySettings().getFirst().roles(PermissionType.SEND))
                .containsExactly("a", "b");
    }

    @Test
    void aBridgeWritesADiscoveryGroupWhenItHasNoStaticConnectors() {
        var b = new BridgeDecl(
                "b", "q", "f", null, null, List.of(), "grp", null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);

        String xml = BrokerXmlCodec.write(doc(List.of(), List.of(), List.of(), List.of(b)));

        assertThat(xml)
                .contains("<discovery-group-ref discovery-group-name=\"grp\"/>")
                .doesNotContain("static-connectors");
        assertThat(BrokerXmlCodec.parse(xml).document().bridges().getFirst().discoveryGroupName())
                .isEqualTo("grp");
    }

    @Test
    void aBridgeCredentialIsNamedInAPlaceholderNeverWritten() {
        var b = new BridgeDecl(
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
                null,
                null,
                null,
                "vault-ref");

        String xml = BrokerXmlCodec.write(doc(List.of(), List.of(), List.of(), List.of(b)));

        assertThat(xml)
                .contains("Credential 'vault-ref' is held in Artemis Studio's vault")
                .contains("<user>${vault-ref.user}</user>")
                .contains("<password>${vault-ref.password}</password>");
    }
}
